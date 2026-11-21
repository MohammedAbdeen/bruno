package com.usebruno.intellij.flows;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.InputSource;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

final class ResponseRendering {
    static String kind(ExecutionResult response) {
        String type = response.contentType().toLowerCase(Locale.ROOT);
        if (type.isBlank()) type = response.headers().toLowerCase(Locale.ROOT);
        if (type.contains("svg") || type.contains("xml")) return "XML";
        if (type.contains("html")) return "HTML";
        if (type.contains("image/") || response.payloadSize() > 0 && imageSignature(response.payload())) return "Image";
        if (type.contains("json") || response.body().stripLeading().startsWith("{") || response.body().stripLeading().startsWith("[")) return "JSON";
        return "Text";
    }
    private static boolean imageSignature(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] == (byte)0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                || bytes[0] == (byte)0xff && bytes[1] == (byte)0xd8 || bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F');
    }
    static BufferedImage image(byte[] bytes) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("This image format is not supported by the IDE image decoder.");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > 16_000_000L) throw new IOException("Image is too large to preview (16 megapixel limit). Save the response to view it externally.");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
    static String xml(String raw) throws Exception {
        if (raw.length() > 1_000_000) return raw;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
        });
        var document = builder.parse(new InputSource(new StringReader(raw)));
        checkXmlDepth(document.getDocumentElement(),0);
        var transformers = TransformerFactory.newInstance(); transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = transformers.newTransformer(); transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        var result = new StringWriter(); transformer.transform(new DOMSource(document), new StreamResult(result)); return result.toString();
    }
    private static final Set<String> HTML_TAGS = Set.of("p","b","strong","i","em","u","h1","h2","h3","h4","ul","ol","li","table","tr","td","th","pre","code","div","span","br","hr","blockquote");
    private static void checkXmlDepth(org.w3c.dom.Node node,int depth) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("XML formatting cancelled.");
        if (depth>128) throw new IOException("XML preview nesting exceeds 128 levels.");
        for (var child=node.getFirstChild();child!=null;child=child.getNextSibling())
            if (child.getNodeType()==org.w3c.dom.Node.ELEMENT_NODE) checkXmlDepth(child,depth+1);
    }

    static String html(String raw) throws IOException {
        StringBuilder out = new StringBuilder("<html><body>");
        new ParserDelegator().parse(new StringReader(raw.substring(0, Math.min(raw.length(), 200_000))), new HTMLEditorKit.ParserCallback() {
            int blocked, count;
            final Deque<String> tags = new ArrayDeque<>();
            boolean blocked(String tag) { return Set.of("head","script","style","object","iframe").contains(tag); }
            @Override public void handleStartTag(HTML.Tag t, javax.swing.text.MutableAttributeSet attrs, int pos) {
                String tag = t.toString();
                if (blocked(tag)) { blocked++; return; }
                if (blocked == 0 && HTML_TAGS.contains(tag) && tags.size() < 32 && count++ < 2000) { out.append('<').append(tag).append('>'); tags.push(tag); }
            }
            @Override public void handleEndTag(HTML.Tag t, int pos) {
                String tag = t.toString();
                if (blocked(tag)) { blocked = Math.max(0, blocked - 1); return; }
                if (blocked == 0 && !tags.isEmpty() && tags.peek().equals(tag)) out.append("</").append(tags.pop()).append('>');
            }
            @Override public void handleSimpleTag(HTML.Tag t, javax.swing.text.MutableAttributeSet attrs, int pos) {
                if (blocked == 0 && HTML_TAGS.contains(t.toString())) out.append('<').append(t).append('>');
                else if (blocked == 0 && t == HTML.Tag.IMG) out.append("[Image omitted from passive HTML preview]");
            }
            @Override public void handleText(char[] text, int pos) { if (blocked == 0) out.append(BrunoUi.html(new String(text))); }
        }, true);
        return out.append("</body></html>").toString();
    }
}
