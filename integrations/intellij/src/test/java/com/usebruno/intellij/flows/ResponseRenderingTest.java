package com.usebruno.intellij.flows;

import org.junit.Test;
import static org.junit.Assert.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

public class ResponseRenderingTest {
    @Test public void imageMimeAndSniffedPayloadRenderWithoutDownloading() throws Exception {
        var pixels = new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB); var bytes = new ByteArrayOutputStream(); ImageIO.write(pixels,"png",bytes);
        var response = new ExecutionResult(200,"HTTP 200",1,bytes.size(),"binary","","",false,true,"").withPayload("application/octet-stream",bytes.toByteArray());
        assertEquals("Image",ResponseRendering.kind(response)); assertEquals(3,ResponseRendering.image(response.payload()).getWidth());
        byte[] copy = response.payload(); copy[0]=0; assertEquals(3,ResponseRendering.image(response.payload()).getWidth());
    }
    @Test public void htmlIsPassiveAndXmlDoesNotLoadExternalEntities() throws Exception {
        String passive = ResponseRendering.html("<html><head><style>@import url(https://invalid.test);</style></head><body><h1>Hello</h1><script>alert(1)</script><img src='https://invalid.test/p.png'><a href='file:///etc/passwd'>Link</a></body></html>");
        assertTrue(passive.contains("<h1>Hello</h1>")); assertFalse(passive.contains("https://")); assertFalse(passive.contains("file://")); assertFalse(passive.contains("script"));
        assertTrue(ResponseRendering.xml("<root><item>ok</item></root>").contains("item"));
        assertThrows(Exception.class, () -> ResponseRendering.xml("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>"));
    }
    @Test public void deeplyNestedXmlIsRejectedBeforeTransformation() {
        var error=assertThrows(Exception.class,() -> ResponseRendering.xml("<node>".repeat(1000)+"text"+"</node>".repeat(1000)));
        assertTrue(error instanceof java.io.IOException || error instanceof org.xml.sax.SAXParseException);
        assertTrue(error.getMessage().contains("depth") || error.getMessage().contains("nesting"));
    }
    @Test public void duplicateUsesLastDispatchedInputsInsteadOfEditedDraft() {
        var draft = new RequestDraft(); draft.fields.url="http://localhost/previous";
        draft.lastExecution = new com.google.gson.Gson().fromJson(draft.snapshot(), RequestDraft.Fields.class);
        draft.fields.url="http://localhost/new-unsent";
        assertEquals("http://localhost/previous",draft.duplicateLastExecution().fields.url);
        assertEquals("http://localhost/new-unsent",draft.fields.url);
    }
}
