package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.*;

/** Source-derived OpenAPI. Never exports captured requests, variable values or credentials. */
final class OpenApiExport {
    private static final java.util.regex.Pattern PATH_VARIABLE = java.util.regex.Pattern.compile("\\{([\\w]+)}");
    private final Project project;
    private final JsonObject schemas = new JsonObject();
    private OpenApiExport(Project project) { this.project = project; }
    static String create(Project project, List<ApiEndpoint> endpoints, String baseUrl) {
        return new OpenApiExport(project).build(endpoints, baseUrl);
    }
    private String build(List<ApiEndpoint> endpoints, String baseUrl) {
        JsonObject spec = new JsonObject(); spec.addProperty("openapi", "3.0.3");
        spec.add("info", object("title", project.getName() + " APIs", "version", "1.0.0"));
        try { var uri = java.net.URI.create(baseUrl); if (uri.getHost() == null || uri.getUserInfo() != null || !List.of("http","https").contains(uri.getScheme())) baseUrl = "http://localhost:8080"; }
        catch (Exception ignored) { baseUrl = "http://localhost:8080"; }
        JsonArray servers = new JsonArray(); servers.add(object("url", baseUrl)); spec.add("servers", servers);
        JsonObject paths = new JsonObject(); JsonArray skipped = new JsonArray();
        for (var endpoint : endpoints) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled();
            if (endpoint.path().contains("${") || !endpoint.path().startsWith("/")) { skipped.add(endpoint.path()); continue; }
            String path = endpoint.path().replaceAll("\\{([\\w]+):[^{}]*}", "{$1}");
            JsonObject item = paths.has(path) ? paths.getAsJsonObject(path) : new JsonObject(); paths.add(path, item);
            for (String verb : endpoint.method().equals("ANY") ? List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS") : List.of(endpoint.method())) {
                String key = verb.toLowerCase(Locale.ROOT);
                if (item.has(key)) {
                    item.getAsJsonObject(key).getAsJsonArray("x-bruno-handlers").add(endpoint.controller() + "." + endpoint.signature()); continue;
                }
                var operation = operation(endpoint, path, verb); item.add(key, operation);
            }
        }
        spec.add("paths", paths); JsonObject components = new JsonObject(); components.add("schemas", schemas); spec.add("components", components);
        if (!skipped.isEmpty()) spec.add("x-bruno-unresolved-paths", skipped);
        return new GsonBuilder().setPrettyPrinting().create().toJson(spec);
    }
    private JsonObject operation(ApiEndpoint endpoint, String path, String verb) {
        JsonObject op = object("summary", endpoint.handler(), "operationId", endpoint.controller() + "." + endpoint.handler() + "_" + verb + "_" + Integer.toUnsignedString((path + endpoint.signature()).hashCode(),16));
        JsonArray tags = new JsonArray(); tags.add(endpoint.simpleController()); op.add("tags", tags);
        JsonArray handlers = new JsonArray(); handlers.add(endpoint.controller() + "." + endpoint.signature()); op.add("x-bruno-handlers", handlers);
        JsonArray parameters = new JsonArray(); Set<String> described = new HashSet<>();
        var method = method(endpoint);
        if (method != null) for (var param : method.getParameterList().getParameters()) {
            for (var annotation : param.getAnnotations()) {
                String name = annotation.getQualifiedName(); if (name == null) continue;
                String location = name.endsWith(".PathVariable") ? "path" : name.endsWith(".RequestParam") ? "query" : name.endsWith(".RequestHeader") ? "header" : null;
                if (location != null) {
                    String external = constant(annotation.findDeclaredAttributeValue("name"));
                    if (external == null || external.isEmpty()) external = constant(annotation.findDeclaredAttributeValue("value"));
                    if (external == null || external.isEmpty()) external = param.getName();
                    JsonObject entry = object("name", external, "in", location);
                    entry.addProperty("required", location.equals("path") || !"false".equals(constant(annotation.findDeclaredAttributeValue("required"))));
                    entry.add("schema", schema(param.getType(), 0)); parameters.add(entry); described.add(location + ":" + external);
                } else if (name.endsWith(".RequestBody")) {
                    JsonObject body = new JsonObject(); body.addProperty("required", !"false".equals(constant(annotation.findDeclaredAttributeValue("required"))));
                    JsonObject media = new JsonObject(); JsonObject json = new JsonObject(); json.add("schema", schema(param.getType(),0)); media.add("application/json", json); body.add("content",media); op.add("requestBody",body);
                }
            }
        }
        var variables = PATH_VARIABLE.matcher(path);
        while (variables.find()) if (described.add("path:" + variables.group(1))) {
            JsonObject entry = object("name", variables.group(1), "in", "path"); entry.addProperty("required",true); entry.add("schema",object("type","string")); parameters.add(entry);
        }
        if (!parameters.isEmpty()) op.add("parameters", parameters);
        JsonObject response = object("description", "Response; actual status depends on the controller");
        if (method != null && method.getReturnType() != null && !method.getReturnType().equals(PsiTypes.voidType())) {
            PsiType type = method.getReturnType();
            if (type instanceof PsiClassType c && c.getCanonicalText().startsWith("org.springframework.http.ResponseEntity<") && c.getParameters().length > 0) type = c.getParameters()[0];
            JsonObject content = new JsonObject(), json = new JsonObject(); json.add("schema",schema(type,0)); content.add("application/json",json); response.add("content",content);
        }
        JsonObject responses = new JsonObject(); responses.add("default",response); op.add("responses",responses); return op;
    }
    private PsiMethod method(ApiEndpoint endpoint) {
        var file = VirtualFileManager.getInstance().findFileByUrl(endpoint.fileUrl()); if (file == null) return null;
        var psi = PsiManager.getInstance(project).findFile(file); if (psi == null) return null;
        return PsiTreeUtil.getParentOfType(psi.findElementAt(endpoint.offset()), PsiMethod.class, false);
    }
    private String constant(PsiAnnotationMemberValue value) {
        Object result = value == null ? null : JavaPsiFacade.getInstance(project).getConstantEvaluationHelper().computeConstantExpression(value);
        return result == null ? null : result.toString();
    }
    private JsonObject schema(PsiType type, int depth) {
        com.intellij.openapi.progress.ProgressManager.checkCanceled();
        String name = type.getCanonicalText();
        if (name.equals("boolean") || name.equals("java.lang.Boolean")) return object("type","boolean");
        if (Set.of("byte","short","int","long","java.lang.Integer","java.lang.Long","java.lang.Short","java.lang.Byte").contains(name)) return object("type","integer");
        if (Set.of("float","double","java.lang.Float","java.lang.Double","java.math.BigDecimal").contains(name)) return object("type","number");
        if (name.equals("java.lang.String") || name.equals("char") || name.equals("java.util.UUID")) return object("type","string");
        if (type instanceof PsiArrayType array) { var value = object("type","array"); value.add("items",schema(array.getComponentType(),depth+1)); return value; }
        if (!(type instanceof PsiClassType c) || depth > 4) return object("type","object");
        if (name.startsWith("java.util.List<") || name.startsWith("java.util.Set<") || name.startsWith("java.util.Collection<")) {
            var value = object("type","array"); value.add("items",c.getParameters().length > 0 ? schema(c.getParameters()[0],depth+1) : new JsonObject()); return value;
        }
        var resolved = c.resolve();
        if (resolved == null || resolved.getQualifiedName() == null || resolved.getQualifiedName().startsWith("java.")) return object("type","object");
        String key = resolved.getQualifiedName() + (c.getParameters().length == 0 ? "" : "_" + Integer.toUnsignedString(name.hashCode(),16));
        var substitutor = c.resolveGenerics().getSubstitutor();
        if (!schemas.has(key)) {
            var definition = object("type", resolved.isEnum() ? "string" : "object"); schemas.add(key,definition);
            if (resolved.isEnum()) {
                JsonArray values = new JsonArray(); for (var field : resolved.getFields()) if (field instanceof PsiEnumConstant) values.add(field.getName()); definition.add("enum",values);
            } else {
                JsonObject properties = new JsonObject();
                for (var field : resolved.getAllFields()) if (!field.hasModifierProperty(PsiModifier.STATIC) && !field.hasAnnotation("com.fasterxml.jackson.annotation.JsonIgnore")) properties.add(field.getName(),schema(Objects.requireNonNullElse(substitutor.substitute(field.getType()),field.getType()),depth+1));
                for (var component : resolved.getRecordComponents()) properties.add(component.getName(),schema(Objects.requireNonNullElse(substitutor.substitute(component.getType()),component.getType()),depth+1));
                definition.add("properties",properties);
            }
        }
        return object("$ref","#/components/schemas/" + key);
    }
    private static JsonObject object(String... values) {
        JsonObject value = new JsonObject(); for (int i=0;i<values.length;i+=2) value.addProperty(values[i],values[i+1]); return value;
    }
}
