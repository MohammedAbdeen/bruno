package com.usebruno.intellij.flows;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.Assert.*;

public class FlowRunHistoryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void preservesStepIoAndMasksKnownCredentialsWithoutDuplicateSnapshots() throws Exception {
        var file=temporary.newFile("report.json").toPath();
        Files.writeString(file,"""
            {"summary":{"status":"success","context":{"total":60,"token":"private-token"},"nodeResults":{"add":{}},"steps":[
              {"nodeId":"add","input":{"context":{"total":0,"password":"hidden-password"},"request":{"headers":[{"name":"Authorization","value":"Bearer private"}]}},
               "output":{"context":{"total":60},"response":{"headers":{"Set-Cookie":["secret-cookie"]}}}}
            ]}}
            """);
        String report=FlowRunHistory.readReport(file); var parsed=JsonParser.parseString(report).getAsJsonObject();
        assertFalse(parsed.has("nodeResults")); assertFalse(report.contains("private-token")); assertFalse(report.contains("hidden-password")); assertFalse(report.contains("Bearer private")); assertFalse(report.contains("secret-cookie"));
        assertEquals(0,parsed.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("input").getAsJsonObject("context").get("total").getAsInt());
        assertEquals(60,parsed.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("output").getAsJsonObject("context").get("total").getAsInt());
    }
    @Test public void boundsRunsAndBytesAndReleasesHistory() {
        var history=new FlowRunHistory(); var large=new JsonObject(); large.addProperty("data","x".repeat(2*1024*1024-100));
        for (int i=0;i<4;i++) history.add(Instant.now(),"flow.flow.bru","local",0,1,large.toString(),"");
        assertTrue(history.runs().stream().mapToLong(FlowRunHistory.Run::bytes).sum()<=FlowRunHistory.MAX_BYTES);
        for (int i=0;i<15;i++) history.add(Instant.now(),"flow"+i+".flow.bru","local",0,1,"{}","");
        assertEquals(10,history.runs().size()); assertEquals("flow14.flow.bru",history.runs().getFirst().flow());
        history.clear(); assertTrue(history.runs().isEmpty());
        history.add(Instant.now(),"flow.flow.bru","",1,0,"{}","startup failed"); assertEquals("failed",history.runs().getFirst().status());
        history.dispose(); assertTrue(history.runs().isEmpty());
        history.add(Instant.now(),"flow.flow.bru","",0,0,"{}",""); assertTrue(history.runs().isEmpty());
    }
    @Test public void malformedReportsCannotBreakHistoryCompletion() {
        var history=new FlowRunHistory();
        for (String report:java.util.List.of("{", "{\"summary\":null}", "{\"steps\":null}", "{\"steps\":[null]}", "{\"steps\":[{\"subFlow\":7}]}",
                "{\"x\":".repeat(100)+"0"+"}".repeat(100))) {
            var run=history.add(Instant.now(),"bad.flow.bru","",1,0,report,"");
            assertEquals("failed",run.status()); assertTrue(run.report().contains("Execution details unavailable"));
        }
        for (String status:java.util.List.of("null","{}","[]","false","7")) {
            var run=history.add(Instant.now(),"status.flow.bru","",0,0,"{\"status\":"+status+"}","");
            assertEquals("success",run.status());
        }
    }
    @Test public void credentialsInsideJsonEncodedBodiesAreRedacted() throws Exception {
        var body=new JsonObject(); body.addProperty("password","private-body"); body.addProperty("id",42);
        var response=new JsonObject(); response.addProperty("body",body.toString());
        var report=new JsonObject(); report.add("response",response);
        var history=new FlowRunHistory();
        var run=history.add(Instant.now(),"body.flow.bru","",0,0,report.toString(),"");
        assertFalse(run.report().contains("private-body"));
        assertEquals(42,JsonParser.parseString(JsonParser.parseString(run.report()).getAsJsonObject().getAsJsonObject("response").get("body").getAsString()).getAsJsonObject().get("id").getAsInt());
    }
    @Test public void oversizedReportsHaveAnExplicitLimitMessage() throws Exception {
        Path file=temporary.newFile("large.json").toPath(); Files.write(file,new byte[FlowRunHistory.MAX_REPORT+1]);
        assertTrue(FlowRunHistory.readReport(file).contains("2 MiB"));
    }
}
