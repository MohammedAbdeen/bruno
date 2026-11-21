package com.usebruno.intellij.flows;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.Assert.*;

public class FlowJsonImportTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final Gson JSON=new Gson();
    static String sample() throws Exception { return Files.readString(Path.of("../../examples/flows/smoke-test/flow-smoke-test.json")); }
    @Test public void acceptsExportRawBomAndPreservesGraphData() throws Exception {
        Path root=temporary.getRoot().toPath();
        var document=FlowJsonImport.prepare("\uFEFF"+sample(),root,false);
        assertEquals("Flow Smoke Test",document.name()); assertEquals(7,document.flow().getAsJsonArray("nodes").size());
        var raw=FlowJsonImport.prepare(JSON.toJson(document.flow()),root,false); assertEquals("Imported Flow",raw.name());
        assertEquals(document.flow(),raw.flow()); assertTrue(document.content().contains("type: flow"));
    }
    @Test public void rejectsInvalidJsonVersionsGraphsAndEscapingReferences() throws Exception {
        Path root=temporary.getRoot().toPath(); String valid=sample();
        for (String invalid:new String[]{"[]","{'nodes':[], 'edges':[]}","{\"nodes\":[],\"edges\":[]} trailing","{\"nodes\":[],\"nodes\":[],\"edges\":[]}",valid.replace("\"version\": 1","\"version\": 2")})
            assertThrows(IOException.class,() -> FlowJsonImport.prepare(invalid,root,false));
        var injected=JsonParser.parseString(valid).getAsJsonObject(); injected.addProperty("name","bad\nname\nblocks");
        assertThrows(IOException.class,() -> FlowJsonImport.prepare(injected.toString(),root,false));
        var parsed=JsonParser.parseString(valid).getAsJsonObject(); var graph=parsed.getAsJsonObject("flow");
        graph.getAsJsonArray("nodes").get(1).getAsJsonObject().addProperty("id","start");
        String duplicate=parsed.toString(); assertThrows(IOException.class,() -> FlowJsonImport.prepare(duplicate,root,false));
        parsed=JsonParser.parseString(valid).getAsJsonObject(); graph=parsed.getAsJsonObject("flow"); graph.getAsJsonArray("edges").get(0).getAsJsonObject().addProperty("target","missing");
        String badEdge=parsed.toString(); assertThrows(IOException.class,() -> FlowJsonImport.prepare(badEdge,root,false));
        for (String ref:new String[]{"../outside.bru","..\\outside.bru","/outside/request.bru","C:\\outside\\request.bru"}) {
            var changed=JsonParser.parseString(valid).getAsJsonObject(); changed.getAsJsonObject("flow").getAsJsonArray("nodes").get(0).getAsJsonObject().getAsJsonObject("data").addProperty("itemRef",ref);
            assertThrows(IOException.class,() -> FlowJsonImport.prepare(changed.toString(),root,false));
        }
    }
    @Test public void normalizesInternalReferencesAndStripsRuntimeState() throws Exception {
        Path root=temporary.getRoot().toPath(); var document=JsonParser.parseString(sample()).getAsJsonObject();
        var data=document.getAsJsonObject("flow").getAsJsonArray("nodes").get(0).getAsJsonObject().getAsJsonObject("data");
        data.addProperty("itemRef",root.resolve("requests/example.bru").toString()); data.addProperty("runStatus","success"); data.add("response",new JsonObject());
        var result=FlowJsonImport.prepare(document.toString(),root,false).flow().getAsJsonArray("nodes").get(0).getAsJsonObject().getAsJsonObject("data");
        assertEquals("requests/example.bru",result.get("itemRef").getAsString()); assertFalse(result.has("runStatus")); assertFalse(result.has("response"));
    }
    @Test public void protectsBruDelimitersAndLimitsSizeAndDepth() throws Exception {
        Path root=temporary.getRoot().toPath(); var document=JsonParser.parseString(sample()).getAsJsonObject();
        document.getAsJsonObject("flow").getAsJsonArray("nodes").get(2).getAsJsonObject().getAsJsonObject("data").addProperty("code","ctx.quoted = \"'''\";");
        var prepared=FlowJsonImport.prepare(document.toString(),root,false); assertTrue(prepared.content().contains("\\u0027"));
        assertEquals(2,prepared.content().split("'''",-1).length-1);
        var file=temporary.newFile("large.json").toPath(); Files.write(file,new byte[FlowJsonImport.MAX_BYTES+1]);
        assertThrows(IOException.class,() -> FlowJsonImport.read(file,root,false));
        String deep="[".repeat(70)+"0"+"]".repeat(70); assertTrue(assertThrows(IOException.class,() -> FlowJsonImport.prepare(deep,root,false)).getMessage().contains("64"));
    }
}
