package com.usebruno.bridge;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.mvc.method.annotation.*;
import org.springframework.web.util.ServletRequestPathUtils;
import jakarta.servlet.DispatcherType;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Separate loopback transport. Calls Spring's adapter, not the servlet filter/interceptor chain. */
public final class BridgeServer implements AutoCloseable {
    private static final int MAX = 16 * 1024 * 1024;
    private final ConfigurableApplicationContext context;
    private final RequestMappingHandlerMapping mapping;
    private final RequestMappingHandlerAdapter adapter;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] authorization;
    private final Gson json = new Gson();
    private record Task(String id, String bean, Method method, String kind, String name) { }

    public BridgeServer(ConfigurableApplicationContext context, RequestMappingHandlerMapping mapping,
                        RequestMappingHandlerAdapter adapter, int port, String token) throws IOException {
        if (token == null || token.length() < 24) throw new IllegalArgumentException("Set bruno.bridge.token to a random token of at least 24 characters.");
        this.context = context; this.mapping = mapping; this.adapter = adapter;
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);
        executor = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32), runnable -> {
            var thread = new Thread(runnable,"bruno-development-bridge"); thread.setDaemon(true); thread.setContextClassLoader(context.getClassLoader()); return thread;
        });
        server.setExecutor(executor); server.createContext("/",this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress() || auth == null
                    || !MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.UTF_8))) {
                send(exchange,401,Map.of("error","A valid development bridge token is required.")); return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) { send(exchange,200,Map.of("name","Bruno development bridge","version",1,"application",context.getEnvironment().getProperty("spring.application.name",context.getId()))); return; }
            if (path.equals("/tasks") && exchange.getRequestMethod().equals("GET")) {
                send(exchange,200,tasks().stream().map(t -> Map.of("id",t.id(),"bean",t.bean(),"method",t.method().getName(),"kind",t.kind(),"name",t.name())).toList()); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange,404,Map.of("error","Unknown bridge operation.")); return; }
            byte[] input = exchange.getRequestBody().readNBytes(MAX + 1);
            if (input.length > MAX) { send(exchange,413,Map.of("error","Bridge input exceeds 16 MiB.")); return; }
            var payload = JsonParser.parseString(new String(input,StandardCharsets.UTF_8)).getAsJsonObject();
            if (path.equals("/invoke")) send(exchange,200,invoke(payload));
            else if (path.equals("/tasks/run")) send(exchange,200,runTask(payload));
            else send(exchange,404,Map.of("error","Unknown bridge operation."));
        } catch (Exception error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            send(exchange,500,Map.of("error",cause.getClass().getSimpleName(),"message",Objects.toString(cause.getMessage(),"Invocation failed")));
        } finally { exchange.close(); }
    }
    private Map<String,Object> invoke(JsonObject payload) throws Exception {
        URI uri = URI.create(payload.get("url").getAsString());
        var request = new MockMultipartHttpServletRequest(); request.setContentType(null); request.removeHeader("Content-Type"); var response = new BoundedResponse(MAX);
        request.setMethod(payload.get("method").getAsString()); request.setRequestURI(uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()); request.setQueryString(uri.getRawQuery());
        request.setScheme(uri.getScheme()); request.setSecure(uri.getScheme().equals("https")); request.setServerName(uri.getHost());
        request.setServerPort(uri.getPort() < 0 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort());
        String prefix = context.getEnvironment().getProperty("server.servlet.context-path","");
        if (!prefix.isBlank() && !prefix.equals("/") && (uri.getPath().equals(prefix) || uri.getPath().startsWith(prefix + "/"))) request.setContextPath(prefix);
        if (payload.has("controller")) request.setAttribute("bruno.expectedController",payload.get("controller").getAsString());
        request.setAsyncSupported(true);
        if (payload.has("principal") && !payload.get("principal").getAsString().isBlank()) { String name = payload.get("principal").getAsString(); request.setUserPrincipal(() -> name); }
        if (payload.has("headers")) payload.getAsJsonObject("headers").entrySet().forEach(e -> e.getValue().getAsJsonArray().forEach(v -> request.addHeader(e.getKey(),v.getAsString())));
        String cookies = request.getHeader("Cookie");
        if (cookies != null) {
            var parsed = new ArrayList<jakarta.servlet.http.Cookie>();
            for (String part : cookies.split(";")) { int equals=part.indexOf('='); if (equals > 0) parsed.add(new jakarta.servlet.http.Cookie(part.substring(0,equals).strip(),part.substring(equals+1).strip())); }
            request.setCookies(parsed.toArray(jakarta.servlet.http.Cookie[]::new));
        }
        byte[] content = Base64.getDecoder().decode(payload.get("body").getAsString()); request.setContent(content);
        parameters(request,uri.getRawQuery());
        if (Objects.toString(request.getContentType(),"").toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) parameters(request,new String(content,StandardCharsets.UTF_8));
        if (Objects.toString(request.getContentType(),"").toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) multipart(request,content);
        ServletRequestPathUtils.parseAndCache(request);
        var previousAttributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var attributes = new org.springframework.web.context.request.ServletRequestAttributes(request,response);
        var previousLocale = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(request.getLocale());
        request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE,context);
        try { return handleController(request,response); }
        finally {
            attributes.requestCompleted();
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(previousAttributes);
            org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previousLocale);
        }
    }
    private Map<String,Object> handleController(MockHttpServletRequest request, BoundedResponse response) throws Exception {
        var chain = mapping.getHandler(request);
        if (chain == null || !(chain.getHandler() instanceof HandlerMethod handler)) { response.setStatus(404); return response(response); }
        String expected = Objects.toString(request.getAttribute("bruno.expectedController"),"");
        if (!expected.isBlank() && !handler.getBeanType().getName().replace('$','.').equals(expected)) throw new IllegalArgumentException("This bridge resolved a different controller. Connect to the application that owns " + expected + ".");
        ModelAndView view;
        try {
            view = adapter.handle(request,response,handler);
            var async = WebAsyncUtils.getAsyncManager(request);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            if (async.isConcurrentHandlingStarted()) {
                while (!async.hasConcurrentResult() && System.nanoTime() < deadline) Thread.sleep(10);
                if (!async.hasConcurrentResult()) throw new IOException("Asynchronous controller did not complete within 30 seconds.");
                request.setDispatcherType(DispatcherType.ASYNC); view = adapter.handle(request,response,handler);
            }
        } catch (Exception error) {
            var resolvers = new ArrayList<>(context.getBeansOfType(HandlerExceptionResolver.class).values()); AnnotationAwareOrderComparator.sort(resolvers);
            view = null; boolean resolved = false;
            for (var resolver : resolvers) { view = resolver.resolveException(request,response,handler,error); if (view != null) { resolved = true; break; } }
            if (!resolved) throw error;
        }
        if (view != null && !view.isEmpty()) {
            View resolved = view.getView();
            if (resolved == null) for (var resolver : context.getBeansOfType(ViewResolver.class).values()) { resolved = resolver.resolveViewName(view.getViewName(),request.getLocale()); if (resolved != null) break; }
            if (resolved == null) throw new IOException("No view resolver is available for this controller result.");
            resolved.render(view.getModel(),request,response);
        }
        return response(response);
    }
    private Map<String,Object> response(MockHttpServletResponse response) throws IOException {
        response.flushBuffer();
        if (response instanceof BoundedResponse bounded && bounded.overflowed()) throw new IOException("Controller response exceeds the development bridge limit.");
        byte[] body = response.getContentAsByteArray(); if (body.length > MAX) throw new IOException("Controller response exceeds the 16 MiB development bridge limit.");
        Map<String,List<String>> headers = new LinkedHashMap<>(); response.getHeaderNames().forEach(name -> headers.put(name,List.copyOf(response.getHeaders(name))));
        return Map.of("status",response.getStatus(),"headers",headers,"body",Base64.getEncoder().encodeToString(body));
    }
    private static void parameters(MockHttpServletRequest request, String query) {
        if (query == null) return;
        for (String entry : query.split("&")) {
            int equals = entry.indexOf('='); String name = equals < 0 ? entry : entry.substring(0,equals), value = equals < 0 ? "" : entry.substring(equals+1);
            request.addParameter(URLDecoder.decode(name,StandardCharsets.UTF_8),URLDecoder.decode(value,StandardCharsets.UTF_8));
        }
    }
    private static void multipart(MockMultipartHttpServletRequest request, byte[] bytes) throws IOException {
        var boundary = java.util.regex.Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(request.getContentType());
        if (!boundary.find()) throw new IOException("Multipart boundary is missing.");
        String value = boundary.group(1) == null ? boundary.group(2) : boundary.group(1);
        if (value.length() > 200) throw new IOException("Invalid multipart boundary.");
        String input = new String(bytes,StandardCharsets.ISO_8859_1);
        for (String part : input.split(java.util.regex.Pattern.quote("--" + value))) {
            if (part.isBlank() || part.startsWith("--")) continue;
            int split = part.indexOf("\r\n\r\n"); if (split < 0) continue;
            String headers = part.substring(0,split), data = part.substring(split+4);
            if (data.endsWith("\r\n")) data = data.substring(0,data.length()-2);
            var name = java.util.regex.Pattern.compile("(?:^|;)\\s*name=\"([^\"]*)\"").matcher(headers);
            if (!name.find()) throw new IOException("Multipart field name is missing.");
            String key = new String(name.group(1).getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8);
            var filename = java.util.regex.Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);
            byte[] content = data.getBytes(StandardCharsets.ISO_8859_1);
            if (filename.find()) {
                var type = java.util.regex.Pattern.compile("(?im)^content-type:\\s*([^\r\n]+)").matcher(headers);
                request.addFile(new MockMultipartFile(key,filename.group(1),type.find() ? type.group(1) : "application/octet-stream",content));
            } else request.addParameter(key,new String(content,StandardCharsets.UTF_8));
        }
    }
    private List<Task> tasks() {
        List<Task> result = new ArrayList<>();
        for (String bean : context.getBeanFactory().getBeanDefinitionNames()) {
            Class<?> type;
            try { Object singleton = context.getBeanFactory().getSingleton(bean); type = singleton == null ? context.getBeanFactory().getType(bean,false) : AopUtils.getTargetClass(singleton); } catch (Exception ignored) { continue; }
            if (type == null) continue;
            for (var method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
                boolean scheduled = !AnnotatedElementUtils.getMergedRepeatableAnnotations(method,Scheduled.class).isEmpty();
                String name = method.getName(), kind = scheduled ? "Scheduled" : "";
                for (Annotation annotation : method.getAnnotations()) if (annotation.annotationType().getName().equals("com.xxl.job.core.handler.annotation.XxlJob")) {
                    kind = "XXL-Job"; try { name = annotation.annotationType().getMethod("value").invoke(annotation).toString(); } catch (Exception ignored) { }
                }
                if (!kind.isEmpty()) result.add(new Task(bean + "#" + method.toGenericString(),bean,method,kind,name));
            }
        }
        return result;
    }
    private Map<String,Object> runTask(JsonObject payload) throws Exception {
        String id = payload.get("id").getAsString();
        var task = tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("This annotated task no longer exists."));
        if (task.method().getParameterCount() != 0) throw new IllegalArgumentException("Scheduled and XXL handlers must be no-argument methods. XXL parameters are exposed through XxlJobHelper.");
        Object previous = null; Method setter = null;
        if (task.kind().equals("XXL-Job")) {
            var cls = Class.forName("com.xxl.job.core.context.XxlJobContext",true,context.getClassLoader());
            previous = cls.getMethod("getXxlJobContext").invoke(null); setter = cls.getMethod("setXxlJobContext",cls);
            String parameter = payload.has("parameter") ? payload.get("parameter").getAsString() : "";
            Object state = cls.getConstructor(long.class,String.class,String.class,int.class,int.class).newInstance(0L,parameter,"",0,1); setter.invoke(null,state);
        }
        long start = System.nanoTime();
        try {
            Object bean = context.getBean(task.bean()); Method method = AopUtils.selectInvocableMethod(task.method(),bean.getClass()); ReflectionUtils.makeAccessible(method);
            Object value = method.invoke(bean);
            return Map.of("task",task.name(),"kind",task.kind(),"durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),"result",Objects.toString(value,"Method invoked"));
        } finally { if (setter != null) setter.invoke(null,previous); }
    }
    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.toJson(value).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
