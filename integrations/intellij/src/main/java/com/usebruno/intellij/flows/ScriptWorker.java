package com.usebruno.intellij.flows;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** One serialized Node channel per project; no pipe reads run on IDE executors. */
@Service(Service.Level.PROJECT)
public final class ScriptWorker implements Disposable {
    private final Project project;
    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile Worker worker;
    private volatile boolean disposed;

    public ScriptWorker(Project project) { this.project = project; }

    Lease acquire(String node, Path module) throws IOException, InterruptedException {
        lock.lockInterruptibly();
        try {
            if (disposed) throw new IOException("Bruno script service is closed.");
            Worker current = worker;
            if (current == null || current.closed.get() || !current.process.isAlive() || !current.node.equals(node) || !current.module.equals(module)) {
                if (current != null) current.close();
                current = new Worker(node, module, project.getBasePath());
                worker = current;
                if (disposed) { current.close(); throw new IOException("Bruno script service is closed."); }
            }
            return new Lease(current);
        } catch (IOException | RuntimeException | InterruptedException error) { lock.unlock(); throw error; }
    }

    final class Lease implements AutoCloseable {
        private final Worker current;
        private boolean complete;
        private Lease(Worker current) { this.current = current; }
        void send(JsonObject message) throws IOException {
            current.writer.write(message.toString()); current.writer.newLine(); current.writer.flush();
        }
        String read() throws IOException, InterruptedException {
            var message = current.messages.poll();
            if (message==null && current.failure!=null) throw current.failure;
            if (message==null) message=current.messages.poll(15,TimeUnit.SECONDS);
            if (message == null) throw new IOException("Script execution exceeded 15 seconds.");
            if (message.error != null) throw message.error;
            return message.line;
        }
        // A terminal protocol message has been consumed; no response can leak into the next hook.
        void complete() { complete = true; }
        @Override public void close() {
            try { if (!complete || Thread.currentThread().isInterrupted()) { current.close(); if (worker == current) worker = null; } }
            finally { lock.unlock(); }
        }
    }

    long processId() { var current = worker; return current == null ? -1 : current.process.pid(); }
    @Override public void dispose() {
        disposed = true;
        var current = worker; if (current != null) current.close();
    }

    private record Message(String line, IOException error) { }
    private static final class Worker {
        final String node;
        final Path module, runner;
        final Process process;
        final BufferedWriter writer;
        final BlockingQueue<Message> messages = new ArrayBlockingQueue<>(8);
        final AtomicBoolean closed = new AtomicBoolean();
        volatile IOException failure;

        Worker(String node, Path module, String projectPath) throws IOException, InterruptedException {
            this.node = node; this.module = module;
            runner = Files.createTempFile("bruno-hooks-", ".cjs");
            Process started = null;
            try {
                try (var source = ScriptWorker.class.getResourceAsStream("/scripts/runner.cjs")) {
                    if (source == null) throw new IOException("Bruno script runner resource is missing.");
                    Files.copy(source, runner, StandardCopyOption.REPLACE_EXISTING);
                }
                var directory = projectPath == null || projectPath.isBlank() ? module : Path.of(projectPath);
                started = new ProcessBuilder(node, runner.toString()).directory(Files.isDirectory(directory) ? directory.toFile() : module.toFile()).start();
                process = started;
                writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                var diagnostic = new StringBuilder();
                var stderr = new Thread(() -> {
                    try (var errors = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                        char[] buffer = new char[1024]; int count;
                        while ((count = errors.read(buffer)) >= 0) synchronized (diagnostic) {
                            int remaining = 4096 - diagnostic.length();
                            if (remaining > 0) diagnostic.append(buffer, 0, Math.min(count, remaining));
                        }
                    } catch (IOException ignored) { }
                }, "Bruno script diagnostics " + process.pid());
                stderr.setDaemon(true); stderr.start();
                var reader = new Thread(this::readMessages, "Bruno script pipe " + process.pid());
                reader.setDaemon(true); reader.start();
                // No request/credential data is sent before readiness, so startup
                // diagnostics can be surfaced without exposing a hook's stderr.
                var ready = messages.poll(5, TimeUnit.SECONDS);
                if (ready == null || ready.error != null || !"{\"kind\":\"ready\"}".equals(ready.line)) {
                    if (!process.isAlive()) stderr.join(200);
                    String detail; synchronized (diagnostic) { detail = diagnostic.toString().strip(); }
                    throw new IOException("Bruno script worker failed to start. " + (detail.isEmpty() ? "Check the Node executable." : detail));
                }
            } catch (IOException | RuntimeException | InterruptedException error) {
                if (started != null) { started.descendants().forEach(ProcessHandle::destroyForcibly); started.destroyForcibly(); }
                Files.deleteIfExists(runner); throw error;
            }
        }

        private void readMessages() {
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer=new char[8192]; var line=new StringBuilder(); int count;
                while (!closed.get() && (count=reader.read(buffer))>=0) {
                    int start=0;
                    for (int i=0;i<count;i++) if (buffer[i]=='\n') {
                        appendLine(line,buffer,start,i-start); offer(line.toString()); line.setLength(0); start=i+1;
                    }
                    appendLine(line,buffer,start,count-start);
                }
                if (!line.isEmpty() && !closed.get()) offer(line.toString());
                throw new IOException("Bruno script worker exited without a result. Check the Node executable and runtime package.");
            } catch (IOException error) { close(error); }
        }

        private static void appendLine(StringBuilder line,char[] buffer,int start,int count) throws IOException {
            if (line.length()+count>24*1024*1024) throw new IOException("Script result exceeds the 24 MiB limit.");
            line.append(buffer,start,count);
        }
        private void offer(String line) throws IOException {
            if (!messages.offer(new Message(line,null))) throw new IOException("Bruno script worker exceeded the message limit.");
        }
        void close() { close(new IOException("Bruno script worker stopped.")); }
        private void close(IOException error) {
            if (!closed.compareAndSet(false, true)) return;
            failure=error;
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            // Preserve already delivered results when EOF follows the terminal message.
            messages.offer(new Message(null,error));
            try { Files.deleteIfExists(runner); } catch (IOException ignored) { runner.toFile().deleteOnExit(); }
        }
    }
}
