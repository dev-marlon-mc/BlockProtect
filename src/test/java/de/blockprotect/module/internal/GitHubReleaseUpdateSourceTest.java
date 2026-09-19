package de.blockprotect.module.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseUpdateSourceTest {
    @Test
    void acceptsModuleJarNamesFromTheBuildAndSimpleReleaseNames() {
        assertTrue(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "BlockProtect-Audit-5.1.0.jar"));
        assertTrue(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "audit-5.1.0.jar"));
        assertTrue(GitHubReleaseUpdateSource.isModuleJarAsset("sessions", "BlockProtect-Sessions-5.1.0.jar"));
    }

    @Test
    void doesNotMatchAnotherModuleOrNonJarAsset() {
        assertFalse(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "BlockProtect-Sessions-5.1.0.jar"));
        assertFalse(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "audit-5.1.0.jar.sha256"));
    }
}
