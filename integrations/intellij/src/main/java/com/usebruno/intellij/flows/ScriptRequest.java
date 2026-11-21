package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import java.util.*;

final class ScriptRequest {
    static RequestComposer.Prepared prepare(Project project, RequestDraft.Fields fields, ApiSettings settings, TransportOptions options) throws Exception {
        if (!fields.preScript.isBlank()) {
            try {
                JsonObject original = request(fields, settings);
                var result = ScriptHooks.run(project,"pre",fields.preScript,fields,settings,original.deepCopy(),null);
                ScriptHooks.apply(project,settings,fields.environment,result);
                var changed = result.request();
                if (!changed.get("url").equals(original.get("url"))) { fields.url = changed.get("url").getAsString(); fields.query = new ArrayList<>(QueryCodec.merge(fields.query, QueryCodec.split(fields.url).rows(), true)); }
                fields.method = changed.get("method").getAsString();
                JsonObject before = original.getAsJsonObject("headers"), after = changed.getAsJsonObject("headers");
                Set<String> keys = new HashSet<>(before.keySet()); keys.addAll(after.keySet());
                for (String key : keys) if (!Objects.equals(before.get(key),after.get(key))) {
                    fields.headers.removeIf(r -> r.key.equalsIgnoreCase(key));
                    var row = new RequestRow(key, after.has(key) ? ScriptHooks.value(after.get(key)) : ""); row.enabled = after.has(key); fields.headers.add(row);
                }
                if (!Objects.equals(original.get("data"),changed.get("data"))) {
                    fields.body = ScriptHooks.value(changed.get("data"));
                    if (!List.of("JSON","Text").contains(fields.bodyMode)) fields.bodyMode = "Text";
                }
            } catch (InterruptedException error) { throw error; }
            catch (Exception error) { throw new IllegalArgumentException(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
        }
        return RequestComposer.prepare(fields,settings,options,true);
    }
    static ExecutionResult post(Project project, RequestDraft.Fields fields, ApiSettings settings, ExecutionResult response) throws Exception {
        if (fields.postScript.isBlank()) return response;
        JsonObject body = new JsonObject(); body.addProperty("status",response.status()); body.addProperty("statusText",response.outcome());
        body.addProperty("responseTime",response.durationMs()); body.addProperty("size",response.bytesRead());
        JsonElement data;
        try { data = JsonParser.parseString(response.body()); } catch (Exception ignored) { data = new JsonPrimitive(response.body()); }
        body.add("data",data); JsonObject headers = new JsonObject();
        response.headers().lines().forEach(line -> {
            int colon = line.indexOf(':'); if (colon <= 0) return;
            String key = line.substring(0,colon).strip().toLowerCase(Locale.ROOT), value = line.substring(colon+1).strip();
            if (!headers.has(key)) headers.addProperty(key,value);
            else { JsonArray values = headers.get(key).isJsonArray() ? headers.getAsJsonArray(key) : new JsonArray(); if (values.isEmpty()) values.add(headers.get(key)); values.add(value); headers.add(key,values); }
        });
        body.add("headers",headers);
        try {
            var result = ScriptHooks.run(project,"post",fields.postScript,fields,settings,request(fields,settings),body.deepCopy());
            ScriptHooks.apply(project,settings,fields.environment,result);
            String value = data.equals(result.response().get("data")) ? response.body() : ScriptHooks.value(result.response().get("data"));
            return new ExecutionResult(response.status(),response.outcome(),response.durationMs(),response.bytesRead(),value,response.headers(),response.details() + "\n" + result.log(),response.truncated(),response.binary(),response.download(),response.curl(),response.contentType(),response.payload());
        } catch (InterruptedException error) { throw error; }
        catch (Exception error) {
            BrunoNotifications.error(project,"Post-response script failed. The HTTP response is available in the inspector.");
            return new ExecutionResult(response.status(),response.outcome() + " · Script error",response.durationMs(),response.bytesRead(),response.body(),response.headers(),response.details()+"\nPost-response script failed: " + error.getMessage(),response.truncated(),response.binary(),response.download(),response.curl(),response.contentType(),response.payload());
        }
    }
    static JsonObject request(RequestDraft.Fields fields, ApiSettings settings) {
        JsonObject request = new JsonObject(); request.addProperty("url",fields.url); request.addProperty("method",fields.method); request.addProperty("data",fields.body);
        JsonObject headers = new JsonObject(); settings.environments.stream().filter(e -> e.name.equals(fields.environment)).findFirst().ifPresent(env -> {
            env.migrate(); env.headerRows.stream().filter(r -> r.enabled).forEach(r -> headers.addProperty(r.key,r.value));
        });
        for (var row : fields.headers) { var keys = new ArrayList<>(headers.keySet()); keys.stream().filter(k -> k.equalsIgnoreCase(row.key)).forEach(headers::remove); if (row.enabled) headers.addProperty(row.key,row.value); }
        if (fields.bodyMode.equals("JSON") && headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Content-Type"))) headers.addProperty("Content-Type","application/json");
        request.add("headers",headers); return request;
    }
}
