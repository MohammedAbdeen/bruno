package com.usebruno.intellij.flows;

record ApiEndpoint(String method, String path, String controller, String handler, String fileUrl, int offset,
                   String module, String originUrl, String signature, int variant) {
    String identity() { return module + "|" + originUrl + "|" + controller + "|" + signature + "|" + variant; }
    String searchable() { return method + " " + path + " " + controller + " " + handler; }
    String searchableLower() { return searchable().toLowerCase(java.util.Locale.ROOT); }
    String simpleController() { return controller.substring(controller.lastIndexOf('.') + 1); }
}
