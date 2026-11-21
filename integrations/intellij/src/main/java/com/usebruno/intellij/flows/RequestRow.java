package com.usebruno.intellij.flows;

import java.util.UUID;

public final class RequestRow {
    public String id = UUID.randomUUID().toString();
    public boolean enabled = true;
    public String key = "";
    public String value = "";
    public String description = "";
    public boolean secret;
    public String secretRef = "";
    public transient boolean secretEdited;

    public RequestRow() {}
    public RequestRow(String key, String value) { this.key = key; this.value = value; }
    public RequestRow copy() {
        RequestRow row = new RequestRow(key, value);
        row.id = id; row.enabled = enabled; row.description = description;
        row.secret = secret; row.secretRef = secretRef; row.secretEdited = secretEdited;
        return row;
    }
}
