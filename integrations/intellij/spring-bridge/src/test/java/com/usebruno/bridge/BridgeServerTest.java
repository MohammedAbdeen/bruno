package com.usebruno.bridge;

import com.google.gson.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.multipart.MultipartFile;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BridgeServerTest {
    private static final String TOKEN = "test-only-012345678901234567890123456789";
    private AnnotationConfigWebApplicationContext context;
    private BridgeServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    @Configuration @EnableWebMvc public static class Config implements WebMvcConfigurer {
        @Bean Demo controller() { return new Demo(); }
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res, Object handler) { res.setStatus(401); return false; }
        }); }
    }
    @RestController public static class Demo {
        final AtomicInteger calls = new AtomicInteger(); String parameter;
        @GetMapping("/async") public java.util.concurrent.CompletableFuture<Map<String,String>> async() { return java.util.concurrent.CompletableFuture.completedFuture(Map.of("result","async complete")); }
        @GetMapping("/private") public Map<String,String> privateApi() { return Map.of("result","controller invoked"); }
        @PostMapping("/echo/{id}") public Map<String,Object> echo(@PathVariable("id") String id, @RequestParam("tag") List<String> tags, @RequestBody Map<String,Object> body, java.security.Principal principal) {
            return Map.of("id",id,"tags",tags,"body",body,"principal",principal.getName());
        }
        @PostMapping("/file") public Map<String,Object> file(@RequestPart("file") MultipartFile file) throws Exception { return Map.of("name",file.getOriginalFilename(),"size",file.getBytes().length); }
        @Scheduled(fixedDelay = 1000) public void scheduled() { calls.incrementAndGet(); }
        @com.xxl.job.core.handler.annotation.XxlJob("parameter-job") public void xxl() { parameter = com.xxl.job.core.context.XxlJobContext.getXxlJobContext().getJobParam(); }
    }
    @Before public void start() throws Exception {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        server = new BridgeServer(context,context.getBean(RequestMappingHandlerMapping.class),context.getBean(RequestMappingHandlerAdapter.class),0,TOKEN); server.start();
    }
    @After public void stop() { if (server != null) server.close(); if (context != null) context.close(); }
    private HttpResponse<String> call(String path, JsonElement body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).header("Authorization","Bearer " + token);
        if (body != null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonObject invoke(String url, String method, String body, JsonObject headers) {
        var payload = new JsonObject(); payload.addProperty("url",url); payload.addProperty("method",method);
        payload.addProperty("body",Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))); payload.add("headers",headers); return payload;
    }
    @Test public void requiresTokenAndBypassesBothFiltersAndInterceptors() throws Exception {
        assertEquals(401,call("/health",null,"incorrect").statusCode()); assertEquals(200,call("/health",null,TOKEN).statusCode());
        var normal = MockMvcBuilders.webAppContextSetup(context).build();
        assertEquals(401,normal.perform(MockMvcRequestBuilders.get("/private")).andReturn().getResponse().getStatus());
        var filtered = MockMvcBuilders.webAppContextSetup(context).addFilters((jakarta.servlet.Filter)(req,res,chain) -> ((jakarta.servlet.http.HttpServletResponse)res).setStatus(403)).build();
        assertEquals(403,filtered.perform(MockMvcRequestBuilders.get("/private")).andReturn().getResponse().getStatus());
        var selected=invoke("http://localhost:8080/private","GET","",new JsonObject());
        selected.addProperty("controller","another.application.Controller"); assertEquals(500,call("/invoke",selected,TOKEN).statusCode());
        selected.addProperty("controller",Demo.class.getName().replace('$','.'));
        var response = call("/invoke",selected,TOKEN);
        assertEquals(response.body(),200,response.statusCode()); var result = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(200,result.get("status").getAsInt()); assertTrue(new String(Base64.getDecoder().decode(result.get("body").getAsString()),StandardCharsets.UTF_8).contains("controller invoked"));
    }
    @Test public void usesSpringBindingForPathQueryBodyPrincipalAndMultipart() throws Exception {
        var headers = new JsonObject(); var type = new JsonArray(); type.add("application/json"); headers.add("Content-Type",type);
        var payload = invoke("http://localhost:8080/echo/42?tag=one&tag=two","POST","{\"active\":true}",headers); payload.addProperty("principal","developer");
        var response = call("/invoke",payload,TOKEN); assertEquals(response.body(),200,response.statusCode());
        var result = JsonParser.parseString(response.body()).getAsJsonObject(); assertEquals(200,result.get("status").getAsInt());
        String body = new String(Base64.getDecoder().decode(result.get("body").getAsString()),StandardCharsets.UTF_8);
        assertTrue(body.contains("developer")); assertTrue(body.contains("two")); assertTrue(body.contains("42"));
        type = new JsonArray(); type.add("multipart/form-data; boundary=sample"); headers.add("Content-Type",type);
        String multipart = "--sample\r\nContent-Disposition: form-data; name=\"file\"; filename=\"data.txt\"\r\nContent-Type: text/plain\r\n\r\nabc\r\n--sample--\r\n";
        response = call("/invoke",invoke("http://localhost:8080/file","POST",multipart,headers),TOKEN);
        result = JsonParser.parseString(response.body()).getAsJsonObject(); assertEquals(response.body(),200,result.get("status").getAsInt());
        assertTrue(new String(Base64.getDecoder().decode(result.get("body").getAsString()),StandardCharsets.UTF_8).contains("data.txt"));
    }
    @Test public void triggersOnlyAnnotatedScheduledAndXxlHandlers() throws Exception {
        var response = call("/tasks",null,TOKEN); var tasks = JsonParser.parseString(response.body()).getAsJsonArray(); assertEquals(2,tasks.size());
        for (var entry : tasks) {
            var payload = new JsonObject(); payload.add("id",entry.getAsJsonObject().get("id")); payload.addProperty("parameter","job-argument");
            response = call("/tasks/run",payload,TOKEN); assertEquals(response.body(),200,response.statusCode());
        }
        assertEquals(1,context.getBean(Demo.class).calls.get()); assertEquals("job-argument",context.getBean(Demo.class).parameter);
        assertNull(com.xxl.job.core.context.XxlJobContext.getXxlJobContext());
        var invalid = new JsonObject(); invalid.addProperty("id","java.lang.Runtime#exec"); assertEquals(500,call("/tasks/run",invalid,TOKEN).statusCode());
    }
    @Test public void completesAsyncControllersAndBoundsResponseBuffers() throws Exception {
        var response = call("/invoke",invoke("http://localhost:8080/async","GET","",new JsonObject()),TOKEN);
        var result = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(response.body(),200,response.statusCode());
        assertTrue(new String(Base64.getDecoder().decode(result.get("body").getAsString()),StandardCharsets.UTF_8).contains("async complete"));
        var bounded = new BoundedResponse(4); bounded.getOutputStream().write(new byte[]{1,2,3});
        assertThrows(java.io.IOException.class, () -> bounded.getOutputStream().write(new byte[]{4,5})); assertTrue(bounded.overflowed());
    }
    @Test public void bridgeIsOptInAndCanBeAutoConfigured() {
        var optional = new AnnotationConfigWebApplicationContext(); optional.setServletContext(new MockServletContext()); optional.register(Config.class,BrunoBridgeAutoConfiguration.class);
        optional.refresh(); try { assertFalse(optional.containsBean("brunoDevelopmentBridge")); } finally { optional.close(); }
        var enabled = new AnnotationConfigWebApplicationContext(); enabled.setServletContext(new MockServletContext());
        enabled.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("bridge",Map.of("bruno.bridge.enabled","true","bruno.bridge.port","0","bruno.bridge.token",TOKEN)));
        enabled.register(Config.class,BrunoBridgeAutoConfiguration.class); enabled.refresh();
        try { assertTrue(enabled.getBean(BridgeServer.class).port() > 0); } finally { enabled.close(); }
    }
}
