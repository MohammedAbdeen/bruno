package com.usebruno.bridge;

import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.*;
import java.io.*;

final class BoundedResponse extends MockHttpServletResponse {
    private final int limit;
    private int written;
    private boolean overflow;
    private ServletOutputStream stream;
    private PrintWriter writer;
    BoundedResponse(int limit) { this.limit = limit; }
    @Override public ServletOutputStream getOutputStream() {
        if (stream == null) {
            var delegate = super.getOutputStream();
            stream = new ServletOutputStream() {
                private void reserve(int count) throws IOException {
                    if (count > limit - written) { overflow = true; throw new IOException("Bridge response exceeds the preview limit."); } written += count;
                }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                @Override public void write(int value) throws IOException { reserve(1); delegate.write(value); }
                @Override public void write(byte[] bytes, int offset, int count) throws IOException { reserve(count); delegate.write(bytes,offset,count); }
                @Override public void flush() throws IOException { delegate.flush(); }
            };
        }
        return stream;
    }
    @Override public PrintWriter getWriter() throws UnsupportedEncodingException {
        if (writer == null) writer = new PrintWriter(new OutputStreamWriter(getOutputStream(),getCharacterEncoding()),true); return writer;
    }
    @Override public void flushBuffer() { if (writer != null) writer.flush(); super.flushBuffer(); }
    boolean overflowed() { return overflow; }
}
