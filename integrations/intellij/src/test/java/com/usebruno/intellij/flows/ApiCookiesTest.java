package com.usebruno.intellij.flows;

import org.junit.Test;
import java.net.*;
import java.util.*;
import static org.junit.Assert.*;

public class ApiCookiesTest {
    @Test public void isolatesProfilesOriginsProjectsAndEnforcesCookieAttributes() throws Exception {
        var store=new ApiCookies(); var otherProject=new ApiCookies();
        URI origin=URI.create("https://example.test:8443/auth/csrf");
        var jar=store.jar("local",origin);
        jar.put(origin,Map.of("Set-Cookie",List.of("SESSION=private; Path=/auth; Secure; HttpOnly")));
        assertTrue(jar.get(URI.create("https://example.test:8443/auth/login"),Map.of()).toString().contains("SESSION=private"));
        assertFalse(jar.get(URI.create("https://example.test:8443/outside"),Map.of()).toString().contains("SESSION=private"));
        assertFalse(jar.get(URI.create("http://example.test:8443/auth/login"),Map.of()).toString().contains("SESSION=private"));
        assertTrue(store.jar("other",origin).getCookieStore().getCookies().isEmpty());
        assertTrue(otherProject.jar("local",origin).getCookieStore().getCookies().isEmpty());
        assertTrue(store.jar("local",URI.create("https://example.test:9443/auth/login")).getCookieStore().getCookies().isEmpty());
        assertTrue(store.jar("local",URI.create("https://other.example.test:8443/auth/login")).getCookieStore().getCookies().isEmpty());
        jar.put(origin,Map.of("Set-Cookie",List.of("SESSION=gone; Path=/auth; Max-Age=0")));
        assertTrue(jar.getCookieStore().getCookies().isEmpty());
        store.dispose(); otherProject.dispose();
    }
    @Test public void boundsCookieRetentionAndReleasesStateOnDispose() throws Exception {
        var store=new ApiCookies(); URI origin=URI.create("http://localhost:8080/");
        var jar=store.jar("local",origin);
        for (int i=0;i<300;i++) jar.put(origin,Map.of("Set-Cookie",List.of("cookie"+i+"=value; Path=/")));
        assertTrue(jar.getCookieStore().getCookies().size()<=128);
        for (int i=0;i<70;i++) store.jar("profile"+i,origin);
        assertTrue("LRU jar must release cookies",jar.getCookieStore().getCookies().isEmpty());
        var active=store.jar("active",origin); active.put(origin,Map.of("Set-Cookie",List.of("SESSION=last; Path=/")));
        store.dispose(); assertTrue(active.getCookieStore().getCookies().isEmpty());
        assertThrows(IllegalStateException.class,() -> store.jar("active",origin));
    }
}
