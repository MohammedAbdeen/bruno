package com.usebruno.intellij.flows;

import com.intellij.credentialStore.*;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
import java.util.UUID;

final class SecretVault {
    // Credential store access runs on workers; previews use the mask without reading credentials.
    static String read(String reference) {
        var credentials = PasswordSafe.getInstance().get(new CredentialAttributes(reference));
        if (credentials == null || credentials.getPasswordAsString() == null)
            throw new IllegalArgumentException("A saved secret is unavailable. Update it in Environments.");
        return credentials.getPasswordAsString();
    }
    static void write(String reference, String value) {
        PasswordSafe.getInstance().set(new CredentialAttributes(reference), new Credentials("Bruno", value));
    }
    static void delete(String reference) {
        if (reference != null && !reference.isBlank()) {
            PasswordSafe.getInstance().set(new CredentialAttributes(reference), null);
        }
    }
    static String projectScope(Project project, String projectId) {
        if (project != null && project.getBasePath() != null) {
            return Integer.toHexString(project.getBasePath().hashCode());
        }
        return (projectId != null && !projectId.isBlank()) ? projectId : "default";
    }
    static String createReference(Project project, String projectId, String scope) {
        return "Bruno/" + projectScope(project, projectId) + "/" + scope + "/" + UUID.randomUUID();
    }
}
