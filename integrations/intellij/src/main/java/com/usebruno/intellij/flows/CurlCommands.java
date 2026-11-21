package com.usebruno.intellij.flows;

import java.net.http.HttpRequest;
import java.util.List;

final class CurlCommands {
    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    static String create(HttpRequest request, String body, List<RequestRow> multipart, List<String> secrets) {
        StringBuilder curl = new StringBuilder("curl --request ").append(quote(request.method()))
                .append(" --url ").append(quote(RequestComposer.Prepared.redact(request.uri().toString(), secrets)));
        request.headers().map().forEach((key, values) -> {
            // cURL creates its own multipart boundary.
            if (!multipart.isEmpty() && key.equalsIgnoreCase("content-type")) return;
            for (String value : values) curl.append(" --header ").append(quote(key + ": " + (RequestComposer.sensitive(key) ? "••••" : RequestComposer.Prepared.redact(value, secrets))));
        });
        if (!multipart.isEmpty()) {
            for (var row : multipart) curl.append(row.description.equals("file") ? " --form " : " --form-string ")
                    .append(quote(RequestComposer.Prepared.redact(row.key + "=" + (row.description.equals("file") ? "@\"" + row.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : row.value), secrets)));
        } else if (request.bodyPublisher().map(p -> p.contentLength() != 0).orElse(false)) curl.append(" --data-binary ").append(quote(RequestComposer.Prepared.redact(body, secrets)));
        return curl.toString();
    }
}
