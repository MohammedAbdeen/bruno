package com.usebruno.intellij.flows;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class QueryCodec {
    private static final java.util.regex.Pattern TEMPLATE = java.util.regex.Pattern.compile("\\{\\{[^{}]+}}");
    record Split(String base, List<RequestRow> rows, String fragment) {}
    static Split split(String url) {
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash);
        String full = hash < 0 ? url : url.substring(0, hash);
        int question = full.indexOf('?');
        if (question < 0) return new Split(full, List.of(), fragment);
        List<RequestRow> rows = new ArrayList<>();
        for (String pair : full.substring(question + 1).split("&", -1)) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            rows.add(new RequestRow(decode(equals < 0 ? pair : pair.substring(0, equals)),
                    decode(equals < 0 ? "" : pair.substring(equals + 1))));
        }
        return new Split(full.substring(0, question), rows, fragment);
    }
    static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String decode(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Malformed percent encoding in query parameters."); }
    }
    static String join(String base, List<RequestRow> rows, String fragment) {
        StringJoiner query = new StringJoiner("&");
        for (RequestRow row : rows) if (row.enabled && !row.key.isBlank()) {
            query.add(templateEncode(row.key) + "=" + templateEncode(row.value));
        }
        return base + (query.length() == 0 ? "" : "?" + query) + fragment;
    }
    // Leave placeholders editable while encoding literal data; their resolved values are encoded at send.
    private static String templateEncode(String value) {
        var matcher = TEMPLATE.matcher(value);
        StringBuilder result = new StringBuilder();
        int previous = 0;
        while (matcher.find()) {
            result.append(encode(value.substring(previous, matcher.start()))).append(matcher.group());
            previous = matcher.end();
        }
        return result.append(encode(value.substring(previous))).toString();
    }
    static List<RequestRow> merge(List<RequestRow> existing, List<RequestRow> parsedFromUrl) {
        return merge(existing, parsedFromUrl, false);
    }
    static List<RequestRow> merge(List<RequestRow> existing, List<RequestRow> parsedFromUrl, boolean preserveDisabled) {
        if (existing == null || existing.isEmpty()) return parsedFromUrl;
        List<RequestRow> result = new ArrayList<>();
        List<RequestRow> remainingExisting = new ArrayList<>(existing);

        for (RequestRow parsed : parsedFromUrl) {
            int matchIdx = -1;
            for (int i = 0; i < remainingExisting.size(); i++) {
                RequestRow cand = remainingExisting.get(i);
                if (cand.enabled && cand.key.equals(parsed.key)) {
                    matchIdx = i;
                    break;
                }
            }
            if (matchIdx < 0) {
                for (int i = 0; i < remainingExisting.size(); i++) {
                    RequestRow cand = remainingExisting.get(i);
                    if (!cand.enabled && cand.key.equals(parsed.key)) {
                        matchIdx = i;
                        break;
                    }
                }
            }
            if (matchIdx >= 0) {
                RequestRow match = remainingExisting.remove(matchIdx);
                RequestRow merged = match.copy();
                merged.value = parsed.value;
                merged.enabled = preserveDisabled ? match.enabled : true;
                result.add(merged);
            } else {
                result.add(parsed);
            }
        }
        for (RequestRow remaining : remainingExisting) {
            if (!remaining.enabled) {
                result.add(remaining);
            }
        }
        return result;
    }
}
