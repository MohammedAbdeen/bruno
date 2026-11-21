package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.application.ApplicationManager;

abstract class BrunoPlatformTestCase extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var global = ApplicationManager.getApplication().getService(GlobalApiSettings.class);
        global.loadState(new GlobalApiSettings());
    }
}
