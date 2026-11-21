package com.usebruno.intellij.flows;

import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.ArrayList;
import java.util.List;

public class SpringEndpointScannerTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        // The distributed platform fixture has no mock JDK sources; supply the
        // core types needed by PSI's compile-time String constant evaluator.
        myFixture.addFileToProject("java/lang/Object.java", "package java.lang; public class Object {}");
        myFixture.addFileToProject("java/lang/String.java", "package java.lang; public final class String {}");
        String pkg = "org.springframework.web.bind.annotation";
        for (String name : List.of("RestController", "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
            myFixture.addFileToProject(pkg.replace('.', '/') + "/" + name + ".java",
                    "package " + pkg + "; public @interface " + name
                    + " { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }");
        }
        myFixture.addFileToProject(pkg.replace('.', '/') + "/RequestMethod.java",
                "package " + pkg + "; public enum RequestMethod { GET, POST, PATCH, DELETE, OPTIONS, HEAD }");
    }

    private SpringEndpointScanner.Result inspect(String source) {
        var file = (PsiJavaFile) myFixture.addFileToProject("sample/Example.java", source);
        var endpoints = new ArrayList<ApiEndpoint>();
        var warnings = new ArrayList<String>();
        SpringEndpointScanner.inspect(file, endpoints, warnings);
        return new SpringEndpointScanner.Result(endpoints, warnings);
    }

    public void testPrefixesArraysConstantsAndMethods() {
        var result = inspect("""
                package sample;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping({"/api", "/v2"})
                class Example {
                  static final String USERS = "/users";
                  @GetMapping(path = {USERS, USERS + "/{id}"}) void users() {}
                  @PostMapping void create() {}
                  @RequestMapping(path="/edit", method={RequestMethod.PATCH, RequestMethod.DELETE}) void edit() {}
                }
                """);
        assertEmpty(result.warnings());
        assertEquals(10, result.endpoints().size());
        assertTrue(result.endpoints().stream().anyMatch(e -> e.method().equals("GET") && e.path().equals("/v2/users/{id}")));
        assertTrue(result.endpoints().stream().anyMatch(e -> e.method().equals("POST") && e.path().equals("/api")));
    }

    public void testInheritedInterfaceMappingAndAnyMethod() {
        var result = inspect("""
                package sample;
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/api") interface Contract {
                  @GetMapping("/inherited") void inherited();
                }
                @RestController class Example implements Contract {
                  public void inherited() {}
                  @RequestMapping("/any") public void any() {}
                }
                """);
        assertEmpty(result.warnings());
        assertTrue(result.endpoints().stream().anyMatch(e -> e.method().equals("GET") && e.path().equals("/api/inherited")));
        assertTrue(result.endpoints().stream().anyMatch(e -> e.method().equals("ANY") && e.path().equals("/api/any")));
    }

    public void testUnresolvedRoutesAreReportedAndNonControllersExcluded() {
        var result = inspect("""
                package sample;
                import org.springframework.web.bind.annotation.*;
                @RestController class Example {
                  @GetMapping(UNKNOWN) void bad() {}
                  @GetMapping("/good") void good() {}
                }
                class Utility { @GetMapping("/not-an-api") void helper() {} }
                """);
        assertEquals(1, result.warnings().size());
        assertEquals(1, result.endpoints().size());
        assertEquals("/good", result.endpoints().getFirst().path());
    }

    public void testTypeAndMethodHttpConditionsAreCombined() {
        var result = inspect("""
                package sample;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping(method=RequestMethod.HEAD)
                class Example { @GetMapping("/a") void get() {} }
                """);
        assertEquals(2, result.endpoints().size());
        assertTrue(result.endpoints().stream().anyMatch(e -> e.method().equals("HEAD")));
        assertTrue(result.endpoints().stream().anyMatch(e -> e.method().equals("GET")));
    }
    public void testControllerAndEndpointGutterMarkers() {
        var file = (PsiJavaFile) myFixture.addFileToProject("sample/GutterController.java", """
                package sample;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/users") class GutterController {
                    @PostMapping void create() {}
                    @GetMapping("/me") void profile() {}
                    void helper() {}
                }
                """);
        var type = file.getClasses()[0];
        assertEquals(2, ControllerGutterProvider.endpoints(type, null).size());
        var markers = new ArrayList<com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<?>>();
        var provider = new ControllerGutterProvider();
        provider.collectNavigationMarkers(type.getNameIdentifier(), markers);
        assertEquals(1, markers.size()); markers.clear();
        provider.collectNavigationMarkers(type.findMethodsByName("create", false)[0].getNameIdentifier(), markers);
        assertEquals(1, markers.size()); markers.clear();
        provider.collectNavigationMarkers(type.findMethodsByName("helper", false)[0].getNameIdentifier(), markers);
        assertTrue(markers.isEmpty());
        var endpoint = ControllerGutterProvider.endpoints(type, type.findMethodsByName("profile", false)[0]).getFirst();
        assertEquals("GET", endpoint.method()); assertEquals("/users/me", endpoint.path());
    }

    public void testOpenApiExportsSourceDtoAndPathParametersWithoutCredentials() {
        myFixture.addFileToProject("org/springframework/web/bind/annotation/RequestBody.java", "package org.springframework.web.bind.annotation; public @interface RequestBody { boolean required() default true; }");
        var file=(PsiJavaFile)myFixture.addFileToProject("sample/ExportController.java", """
                package sample;
                import org.springframework.web.bind.annotation.*;
                @RestController class ExportController {
                    @PostMapping("/items/{id}") Payload create(@RequestBody Payload body) { return body; }
                }
                class Payload { String name; int count; }
                """);
        var endpoints = new ArrayList<ApiEndpoint>(); SpringEndpointScanner.inspect(file,endpoints,new ArrayList<>());
        var spec=com.google.gson.JsonParser.parseString(OpenApiExport.create(getProject(),endpoints,"http://localhost:8080")).getAsJsonObject();
        assertEquals("3.0.3",spec.get("openapi").getAsString());
        var op=spec.getAsJsonObject("paths").getAsJsonObject("/items/{id}").getAsJsonObject("post");
        assertTrue(op.has("requestBody"));
        assertEquals("id",op.getAsJsonArray("parameters").get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(spec.getAsJsonObject("components").getAsJsonObject("schemas").has("sample.Payload"));
        assertFalse(spec.toString().contains("Authorization"));
    }

}
