package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.xmlb.XmlSerializer;

public class ApiSettingsTest extends BrunoPlatformTestCase {
    public void testNamedEnvironmentsRoundTrip() {
        var settings = new ApiSettings();
        var stage = new ApiSettings.Environment();
        stage.name = "Staging";
        stage.baseUrl = "https://staging.example.test";
        stage.headers = "Authorization=Bearer {{token}}";
        stage.variables = "token=local-value==\nid=42";
        settings.environments.add(stage);
        settings.selected = stage.name;
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings), ApiSettings.class);
        assertEquals("Staging", restored.selected);
        assertEquals(2, restored.environments.size());
        assertEquals(stage.variables, restored.environments.get(1).variables);
        assertEquals(stage.headers, restored.environments.get(1).headers);
    }

    public void testSecretVaultProjectScopeIsDeterministic() {
        String scope1 = SecretVault.projectScope(getProject(), "fallback-id");
        String scope2 = SecretVault.projectScope(getProject(), "another-id");
        assertEquals("Project scope should be deterministic across re-creations", scope1, scope2);
        assertTrue(scope1.length() > 0);
    }

    public void testLegacyEnvironmentRetainsRawTextOnMigrationFailure() {
        var broken = new ApiSettings.Environment();
        broken.name = "BrokenLegacy";
        broken.variables = "invalid-row-without-equals";
        broken.headers = "valid=header";
        broken.structured = false;

        assertThrows(IllegalArgumentException.class, broken::migrate);
        assertFalse(broken.structured);
        assertEquals("invalid-row-without-equals", broken.variables);
        assertEquals("valid=header", broken.headers);
    }

    public void testExplorerOpensWithRegisteredProjectServiceAndDisposes() {
        assertNotNull(ApiSettings.get(getProject()));
        var panel = new ApiExplorerPanel(getProject());
        try {
            panel.setSize(1000, 800);
            panel.doLayout();
            assertEquals("Local", ApiSettings.get(getProject()).selected);
        } finally {
            Disposer.dispose(panel);
        }
    }
}
