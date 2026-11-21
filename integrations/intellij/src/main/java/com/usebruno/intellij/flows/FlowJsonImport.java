package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;

/** Dependency-free import: reading/validation/serialization runs before any VFS mutation. */
final class FlowJsonImport {
    static final int MAX_BYTES=10*1024*1024;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> TYPES=Set.of("start","request","condition","delay","script","end","for-each","while","sub-flow","assert","data-source");
    record Prepared(String name,String stem,JsonObject flow,boolean yaml,String content) { }
    static Prepared read(Path source,Path collection,boolean yaml) throws IOException {
        byte[] bytes;
        try (var input=Files.newInputStream(source)) { bytes=input.readNBytes(MAX_BYTES+1); }
        if (bytes.length>MAX_BYTES) throw new IOException("Flow JSON must be 10 MiB or smaller.");
        return prepare(new String(bytes,StandardCharsets.UTF_8),collection,yaml);
    }
    static Prepared prepare(String text,Path collection,boolean yaml) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IOException("Flow JSON must be 10 MiB or smaller.");
        JsonElement parsed=parseJson(text);
        JsonObject document=object(parsed,"Flow JSON");
        boolean wrapped=document.has("flow");
        if (wrapped && (!"bruno-flow".equals(string(document,"type")) || !document.has("version") || !document.get("version").isJsonPrimitive()
                || !document.getAsJsonPrimitive("version").isNumber() || document.get("version").getAsBigDecimal().compareTo(BigDecimal.ONE)!=0))
            throw new IOException("Unsupported flow JSON format/version. Expected type bruno-flow and version 1.");
        String name=wrapped && document.has("name") && !document.get("name").isJsonNull() ? requiredString(document,"name").strip() : "Imported Flow";
        if (name.isEmpty()) name="Imported Flow";
        if (name.length()>255 || name.matches("(?s).*[<>:\"/\\\\|?*{}\\p{Cntrl}].*") || name.contains("'''")) throw new IOException("Invalid flow name. Use a single-line name without file-path or Bru delimiters.");
        JsonObject source=wrapped ? object(document.get("flow"),"flow") : document;
        for (String key:source.keySet()) if (!Set.of("nodes","edges","viewport","config").contains(key)) throw new IOException("Unsupported graph property: "+key);
        JsonArray nodes=array(source.get("nodes"),"nodes"),edges=array(source.get("edges"),"edges");
        Set<String> nodeIds=new HashSet<>(),edgeIds=new HashSet<>();
        JsonArray normalizedNodes=new JsonArray(),normalizedEdges=new JsonArray();
        for (var entry:nodes) {
            var node=object(entry,"node"); String id=requiredString(node,"id"),type=requiredString(node,"type");
            if (!nodeIds.add(id)) throw new IOException("Duplicate node ID: "+id);
            if (!TYPES.contains(type)) throw new IOException("Unsupported node type: "+type);
            var data=node.has("data") && !node.get("data").isJsonNull() ? object(node.get("data"),"node data").deepCopy() : new JsonObject();
            for (String key:List.of("runStatus","conditionMet","response")) data.remove(key);
            for (String key:List.of("itemRef","flowRef","fileRef")) if (data.has(key) && !data.get(key).isJsonNull()) {
                String ref=requiredString(data,key); data.addProperty(key,reference(collection,ref));
            }
            var result=new JsonObject(); result.addProperty("id",id); result.addProperty("type",type);
            result.add("position",coordinates(node.get("position"),false)); result.add("data",data); normalizedNodes.add(result);
        }
        for (var entry:edges) {
            var edge=object(entry,"edge"); String id=requiredString(edge,"id"),from=requiredString(edge,"source"),to=requiredString(edge,"target");
            if (!edgeIds.add(id)) throw new IOException("Duplicate edge ID: "+id);
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) throw new IOException("Connection references a missing node: "+id);
            var result=new JsonObject(); result.addProperty("id",id); result.addProperty("source",from); result.addProperty("target",to);
            for (String key:List.of("sourceHandle","targetHandle","label")) if (edge.has(key)) {
                if (!edge.get(key).isJsonNull() && (!edge.get(key).isJsonPrimitive() || !edge.getAsJsonPrimitive(key).isString())) throw new IOException(key+" must be text.");
                result.add(key,edge.get(key).deepCopy());
            }
            normalizedEdges.add(result);
        }
        var graph=new JsonObject(); graph.add("nodes",normalizedNodes); graph.add("edges",normalizedEdges);
        graph.add("viewport",coordinates(source.get("viewport"),true));
        graph.add("config",source.has("config") && !source.get("config").isJsonNull() ? object(source.get("config"),"config").deepCopy() : new JsonObject());
        String stem=name.replaceAll("[^\\p{L}\\p{N}._-]+","-").replaceAll("^[.-]+|[.-]+$","");
        stem=stem.codePoints().limit(50).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
        if (stem.isBlank()) stem="imported-flow";
        if (stem.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?")) stem="flow-"+stem;
        String content;
        if (yaml) {
            var info=new JsonObject(); info.addProperty("name",name); info.addProperty("type","flow"); info.addProperty("seq",1);
            var item=new JsonObject(); item.add("info",info); item.add("flow",graph);
            content=JSON.toJson(item)+"\n"; // JSON is valid YAML; preserves arbitrary strings without a YAML runtime.
        } else {
            String payload=JSON.toJson(graph).replace("'","\\u0027").indent(4);
            content="meta {\n  name: "+name+"\n  type: flow\n  seq: 1\n}\n\nflow {\n  data: '''\n"+payload+"  '''\n}\n";
        }
        return new Prepared(name,stem,graph,yaml,content);
    }
    static JsonElement parseJson(String text) throws IOException {
        if (text.startsWith("\uFEFF")) text=text.substring(1);
        JsonElement parsed;
        try (var reader=new JsonReader(new StringReader(text))) {
            reader.setLenient(false); parsed=value(reader,0);
            if (reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Unexpected content after the JSON object.");
        } catch (IllegalArgumentException | IllegalStateException error) { throw new IOException("Invalid flow JSON: "+error.getMessage(),error); }
        return parsed;
    }
    private static String reference(Path collection,String value) throws IOException {
        if (value.isEmpty()) return value; String portable=value.replace('\\','/');
        try {
            Path root=collection.toAbsolutePath().normalize(),path=Path.of(portable);
            if (portable.matches("^[A-Za-z]:/.*") && !path.isAbsolute()) throw new IOException("Foreign absolute file reference: "+value);
            Path relative=path.isAbsolute() ? root.relativize(path.normalize()) : path.normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("File reference must stay inside the destination collection: "+value);
            return relative.toString().replace('\\','/');
        } catch (IllegalArgumentException error) { throw new IOException("Invalid collection file reference: "+value,error); }
    }
    private static JsonObject coordinates(JsonElement input,boolean viewport) throws IOException {
        var result=input==null || input.isJsonNull() ? new JsonObject() : object(input,viewport ? "viewport" : "position").deepCopy();
        for (String key:viewport ? List.of("x","y","zoom") : List.of("x","y")) {
            if (!result.has(key)) result.addProperty(key,key.equals("zoom") ? 1 : 0);
            var value=result.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IOException(key+" must be a finite number.");
        }
        return result;
    }
    private static JsonObject object(JsonElement value,String field) throws IOException { if (value==null || !value.isJsonObject()) throw new IOException(field+" must be an object."); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value,String field) throws IOException { if (value==null || !value.isJsonArray()) throw new IOException(field+" must be an array."); return value.getAsJsonArray(); }
    private static String string(JsonObject value,String key) { return value.has(key) && value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isString() ? value.get(key).getAsString() : ""; }
    private static String requiredString(JsonObject value,String key) throws IOException {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()) throw new IOException(key+" must be text.");
        String result=value.get(key).getAsString();
        if (result.isBlank() && !List.of("itemRef","flowRef","fileRef","name").contains(key)) throw new IOException(key+" cannot be empty."); return result;
    }
    private static JsonElement value(JsonReader reader,int depth) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("JSON processing cancelled.");
        if (depth>64) throw new IOException("Flow JSON nesting exceeds 64 levels.");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object=new JsonObject(); reader.beginObject();
                while (reader.hasNext()) { String key=reader.nextName(); if (object.has(key)) throw new IOException("Duplicate JSON property: "+key); object.add(key,value(reader,depth+1)); }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> { var array=new JsonArray(); reader.beginArray(); while (reader.hasNext()) array.add(value(reader,depth+1)); reader.endArray(); yield array; }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid flow JSON value.");
        };
    }
}
