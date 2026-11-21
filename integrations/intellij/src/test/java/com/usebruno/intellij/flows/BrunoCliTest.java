package com.usebruno.intellij.flows;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class BrunoCliTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Path script(Path root,String relative) throws Exception {
        Path file=root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file,""); return file.toRealPath();
    }
    private FlowFile flow(Path root) throws Exception { Files.createDirectories(root.resolve("flows")); return new FlowFile(root.resolve("flows/Smoke flow.flow.bru"),root); }
    @Test public void externalCheckoutAndNodePathsPreserveArgumentsAndEnvironment() throws Exception {
        Path project=temporary.newFolder("Java project").toPath(),checkout=temporary.newFolder("Bruno checkout").toPath();
        Path cli=script(checkout,"packages/bruno-cli/bin/bru.js"); var selected=flow(project);
        var preferences=new BrunoPreferences(); preferences.flowCliPath=checkout.toString(); preferences.nodeExecutable="/custom node/bin/node";
        assertEquals(List.of("/custom node/bin/node",cli.toString(),"run","--flow","flows/Smoke flow.flow.bru","--env","staging"),BrunoCli.command(project,selected," staging ",preferences));
        preferences.flowCliPath=cli.toString();
        assertEquals(cli.toString(),BrunoCli.command(project,selected,"",preferences).get(1));
    }
    @Test public void findsCollectionCliAndSiblingOfConfiguredScriptRuntimeWithoutProjectBase() throws Exception {
        Path project=temporary.newFolder("Java").toPath(),collection=temporary.newFolder("Collection").toPath(),checkout=temporary.newFolder("Checkout").toPath();
        var preferences=new BrunoPreferences(); var selected=flow(collection);
        Path sibling=script(checkout,"packages/bruno-cli/bin/bru.js");
        preferences.jsRuntimePath=checkout.resolve("packages/bruno-js").toString();
        assertEquals(sibling.toString(),BrunoCli.command(project,selected,"",preferences).get(1));
        Path local=script(collection,"node_modules/@usebruno/cli/bin/bru.js");
        assertEquals(local.toString(),BrunoCli.command(null,selected,null,preferences).get(1));
        preferences.flowCliPath=collection.resolve("does-not-exist").toString();
        assertTrue(assertThrows(IllegalArgumentException.class,() -> BrunoCli.command(project,selected,"",preferences)).getMessage().contains("Settings"));
    }
    @Test public void configuredForkCliRunsSavedFlowFromExternalJavaProject() throws Exception {
        Path project=temporary.newFolder("External Java project").toPath(); var selected=flow(project);
        Files.writeString(project.resolve("bruno.json"),"{\"version\":\"1\",\"name\":\"Runner test\",\"type\":\"collection\"}");
        Files.writeString(selected.path(),FlowFileOperations.starter("Smoke flow"));
        var preferences=new BrunoPreferences(); preferences.flowCliPath=Path.of("../..","packages/bruno-cli/bin/bru.js").toAbsolutePath().normalize().toString();
        String nodeName=com.intellij.openapi.util.SystemInfo.isWindows ? "node.exe" : "node";
        preferences.nodeExecutable=Arrays.stream(Objects.toString(System.getenv("PATH"),"").split(java.io.File.pathSeparator))
                .map(dir -> Path.of(dir).resolve(nodeName)).filter(Files::isExecutable).findFirst().orElseThrow().toAbsolutePath().toString();
        Path log=project.resolve("output.log");
        var command=BrunoCli.command(project,selected,"",preferences);
        Path report=project.resolve("run-report.json"); command.addAll(List.of("--reporter-json",report.toString()));
        var builder=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PATH",""); // Model desktop IDEs without shell/NVM initialization.
        var process=builder.start();
        try {
            assertTrue("CLI must finish",process.waitFor(20,TimeUnit.SECONDS));
            String output=Files.readString(log); assertEquals(output,0,process.exitValue());
            assertTrue(output,output.contains("Flow success:"));
            var summary=com.google.gson.JsonParser.parseString(FlowRunHistory.readReport(report)).getAsJsonObject();
            assertEquals("success",summary.get("status").getAsString()); assertEquals(2,summary.getAsJsonArray("steps").size());
            var start=summary.getAsJsonArray("steps").get(0).getAsJsonObject(); assertTrue(start.has("input")); assertTrue(start.has("output"));
            assertFalse(output.toLowerCase(Locale.ROOT).contains("unknown argument"));
        } finally { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
    }
}
