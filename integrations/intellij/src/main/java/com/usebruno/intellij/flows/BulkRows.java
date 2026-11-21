package com.usebruno.intellij.flows;

import java.util.*;

final class BulkRows {
    static String format(List<RequestRow> rows) {
        StringJoiner text = new StringJoiner("\n");
        for (RequestRow row : rows) {
            if (row.secret && (row.secretRef.isEmpty() || row.secretEdited)) throw new IllegalArgumentException("Save new or changed secrets before opening Bulk Edit.");
            String value = row.secret ? "{{secret:" + row.secretRef + "}}" : row.value;
            text.add((row.enabled ? "" : "# ") + escape(row.key) + "=" + escape(value)
                    + (row.description.isEmpty() ? "" : "\t" + escape(row.description)));
        }
        return text.toString();
    }

    static List<RequestRow> parse(String text, List<RequestRow> old, boolean unique) {
        List<RequestRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> usedIds = new HashSet<>();
        int number = 0;
        for (String line : text.split("\\R", -1)) {
            number++;
            if (line.isBlank()) continue;
            boolean enabled = !line.startsWith("# ");
            if (!enabled) line = line.substring(2);
            int equals = separator(line, '=');
            if (equals < 1) throw new IllegalArgumentException("Line " + number + ": expected key=value.");
            int tab = separator(line, '\t');
            if (tab >= 0 && tab < equals) throw new IllegalArgumentException("Line " + number + ": description must follow the value.");
            RequestRow row = new RequestRow(unescape(line.substring(0, equals)),
                    unescape(line.substring(equals + 1, tab < 0 ? line.length() : tab)));
            if (row.key.isBlank()) throw new IllegalArgumentException("Line " + number + ": key is empty.");
            if (unique && !keys.add(row.key)) throw new IllegalArgumentException("Line " + number + ": duplicate key " + row.key);
            row.description = tab < 0 ? "" : unescape(line.substring(tab + 1));
            row.enabled = enabled;
            if (row.value.startsWith("{{secret:") && row.value.endsWith("}}")) {
                String reference = row.value.substring(9, row.value.length() - 2);
                if (old.stream().noneMatch(r -> r.secret && r.secretRef.equals(reference)))
                    throw new IllegalArgumentException("Line " + number + ": unknown secret reference.");
                row.secret = true; row.secretRef = reference; row.value = "";
            }
            int index = rows.size();
            RequestRow original = old.stream().filter(r -> !usedIds.contains(r.id) && r.key.equals(row.key)
                    && r.value.equals(row.value) && r.enabled == row.enabled && r.description.equals(row.description)
                    && r.secretRef.equals(row.secretRef)).findFirst().orElse(null);
            if (original != null) row.id = original.id;
            else if (index < old.size() && !usedIds.contains(old.get(index).id)) row.id = old.get(index).id;
            usedIds.add(row.id);
            rows.add(row);
        }
        return rows;
    }

    private static int separator(String text, char needle) {
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!escaped && c == needle) return i;
            if (!escaped && c == '\\') escaped = true; else escaped = false;
        }
        return -1;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t").replace("=", "\\=").replace("#", "\\#");
    }
    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { result.append(c); continue; }
            if (++i == value.length()) throw new IllegalArgumentException("Incomplete escape at end of line.");
            result.append(switch (value.charAt(i)) {
                case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                case '\\', '=', '#' -> value.charAt(i);
                default -> throw new IllegalArgumentException("Unknown escape: \\" + value.charAt(i));
            });
        }
        return result.toString();
    }
}
