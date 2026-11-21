package com.usebruno.intellij.flows;

import java.util.*;
import java.util.regex.*;

final class RequestTemplates {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");

    static Map<String, String> pairs(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        int line = 0;
        for (String row : text.split("\\R")) {
            line++;
            if (row.isBlank() || row.stripLeading().startsWith("#")) continue;
            int separator = row.indexOf('=');
            if (separator < 1 || row.substring(0, separator).isBlank()) {
                throw new IllegalArgumentException("Line " + line + " must use name=value.");
            }
            String name = row.substring(0, separator).trim();
            if (result.containsKey(name)) throw new IllegalArgumentException("Duplicate key: " + name);
            result.put(name, row.substring(separator + 1).trim());
        }
        return result;
    }

    static final int MAX_EXPANDED_CHARS = 1024 * 1024;
    static String expand(String template, Map<String, String> variables) {
        return expand(template, variables, new LinkedHashSet<>(), new HashMap<>());
    }
    private static String expand(String template, Map<String, String> variables, Set<String> stack, Map<String,String> resolved) {
        Matcher matcher=VARIABLE.matcher(template);
        if (!matcher.find()) return template;
        if (template.length()>MAX_EXPANDED_CHARS) throw new IllegalArgumentException("Expanded request field exceeds the 1 MiB character limit.");
        StringBuilder result=new StringBuilder(); int end=0;
        do {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Variable expansion cancelled.");
            String name=matcher.group(1), value=resolved.get(name);
            if (value==null) {
                if (!variables.containsKey(name)) throw new IllegalArgumentException("Missing environment variable: "+name);
                if (!stack.add(name)) throw new IllegalArgumentException("Circular environment variable: "+name);
                if (stack.size()>64) throw new IllegalArgumentException("Environment variables are nested too deeply.");
                value=expand(variables.get(name),variables,stack,resolved);
                stack.remove(name); resolved.put(name,value);
            }
            appendBounded(result,template,end,matcher.start());
            appendBounded(result,value,0,value.length()); end=matcher.end();
        } while (matcher.find());
        appendBounded(result,template,end,template.length()); return result.toString();
    }
    private static void appendBounded(StringBuilder result,String text,int start,int end) {
        if ((long)result.length()+end-start>MAX_EXPANDED_CHARS) throw new IllegalArgumentException("Expanded request field exceeds the 1 MiB character limit.");
        result.append(text,start,end);
    }
}
