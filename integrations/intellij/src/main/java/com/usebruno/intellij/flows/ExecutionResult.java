package com.usebruno.intellij.flows;

record ExecutionResult(int status, String outcome, long durationMs, long bytesRead, String body,
                       String headers, String details, boolean truncated, boolean binary, String download, String curl, String contentType, byte[] payload) {
    ExecutionResult { payload = payload == null ? new byte[0] : payload.clone(); }
    @Override public byte[] payload() { return payload.clone(); }
    ExecutionResult(int status, String outcome, long durationMs, long bytesRead, String body,
                    String headers, String details, boolean truncated, boolean binary, String download) {
        this(status, outcome, durationMs, bytesRead, body, headers, details, truncated, binary, download, "", "", new byte[0]);
    }
    long payloadSize() { return payload.length; }
    ExecutionResult withPayload(String type, byte[] bytes) {
        return new ExecutionResult(status, outcome, durationMs, bytesRead, body, headers, details, truncated, binary, download, curl, type, bytes);
    }
    ExecutionResult withCurl(String value) {
        return new ExecutionResult(status, outcome, durationMs, bytesRead, body, headers, details, truncated, binary, download, value, contentType, payload);
    }
}
