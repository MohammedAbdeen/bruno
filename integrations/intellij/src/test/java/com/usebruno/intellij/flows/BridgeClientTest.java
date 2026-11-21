package com.usebruno.intellij.flows;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;

public class BridgeClientTest {
    @Test public void malformedRepliesExplainTheExpectedCompanionResponse() {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:8080/")).build();
        for (String json : List.of("null", "[]", "{}", "{\"status\":200,\"headers\":{},\"body\":null}",
                "{\"status\":200,\"headers\":{},\"body\":\"%%%\"}", "{\"status\":0,\"headers\":{},\"body\":\"\"}")) {
            var error = assertThrows(IllegalArgumentException.class,() -> BridgeClient.decodeResponse(request,JsonParser.parseString(json)));
            assertTrue(error.getMessage().startsWith("Invalid development bridge response."));
        }
    }

    @Test public void confinesCredentialsToLoopbackAndPreservesApplicationStatus() throws Exception {
        assertThrows(Exception.class,() -> BridgeClient.base("https://example.com"));
        assertThrows(Exception.class,() -> BridgeClient.base("http://192.0.2.1:19090"));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var payload=new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        server.createContext("/invoke",exchange -> {
            assertEquals("Bearer bridge-token",exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response="{\"status\":201,\"headers\":{\"Content-Type\":[\"application/json\"]},\"body\":\"e30=\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try(var client=HttpClient.newHttpClient()) {
            var original=HttpRequest.newBuilder(URI.create("http://localhost:8080/items?id=42")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"active\":true}")).build();
            var response=BridgeClient.invokeWithToken(client,original,"http://127.0.0.1:"+server.getAddress().getPort(),"bridge-token","developer");
            assertEquals(201,response.statusCode()); assertEquals("{}",new String(response.body().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(original.uri().toString(),payload.get().get("url").getAsString());
            assertEquals("{\"active\":true}",new String(Base64.getDecoder().decode(payload.get().get("body").getAsString()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("developer",payload.get().get("principal").getAsString());
        } finally { server.stop(0); }
    }
}
