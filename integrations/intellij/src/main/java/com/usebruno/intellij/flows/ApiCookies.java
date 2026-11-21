package com.usebruno.intellij.flows;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Session-only cookies, isolated by project service, profile identity and exact origin. */
@Service(Service.Level.PROJECT)
public final class ApiCookies implements Disposable {
    private final Map<String,CookieManager> jars = new LinkedHashMap<>(16,0.75f,true);
    private boolean disposed;
    synchronized CookieManager jar(String profile, URI uri) {
        if (disposed) throw new IllegalStateException("The cookie session is closed.");
        String key = profile + "\n" + RequestExecution.origin(uri);
        var manager = jars.get(key);
        if (manager == null) {
            manager = new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER) {
                @Override public void put(URI target,Map<String,List<String>> headers) throws IOException {
                    Map<String,List<String>> accepted=new LinkedHashMap<>();
                    headers.forEach((name,values) -> {
                        if (name.equalsIgnoreCase("Set-Cookie") || name.equalsIgnoreCase("Set-Cookie2"))
                            accepted.put(name,values.stream().filter(value -> value.length()<=8192).limit(128).toList());
                    });
                    super.put(target,accepted);
                    var cookies = getCookieStore().getCookies();
                    for (int i=0; i<cookies.size()-128; i++) getCookieStore().remove(null,cookies.get(i));
                }
            };
            jars.put(key,manager);
            while (jars.size()>32) {
                var oldest=jars.entrySet().iterator(); oldest.next().getValue().getCookieStore().removeAll(); oldest.remove();
            }
        }
        return manager;
    }
    synchronized void clear(String profile) {
        jars.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(profile+"\n")) return false;
            entry.getValue().getCookieStore().removeAll(); return true;
        });
    }
    @Override public synchronized void dispose() {
        disposed=true; jars.values().forEach(jar -> jar.getCookieStore().removeAll()); jars.clear();
    }
}
