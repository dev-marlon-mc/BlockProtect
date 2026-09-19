package de.blockprotect.module.internal;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseUpdateSourceTest {
    @Test
    void acceptsModuleJarNamesFromTheBuildAndSimpleReleaseNames() {
        assertTrue(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "BlockProtect-Audit-26.2.jar"));
        assertTrue(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "audit-26.2.jar"));
        assertTrue(GitHubReleaseUpdateSource.isModuleJarAsset("sessions", "BlockProtect-Sessions-26.2.jar"));
    }

    @Test
    void doesNotMatchAnotherModuleOrNonJarAsset() {
        assertFalse(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "BlockProtect-Sessions-26.2.jar"));
        assertFalse(GitHubReleaseUpdateSource.isModuleJarAsset("audit", "audit-26.2.jar.sha256"));
    }

    @Test
    void extractsAuthenticatedApiAssetUrlsFromReleaseJson() {
        String json = """
                {"assets":[
                  {"url":"https://api.github.com/repos/dev-marlon-mc/BlockProtect/releases/assets/101",
                   "id":101,"node_id":"RA_kwDOExample","name":"BlockProtect-Audit-26.2.jar",
                   "label":null,"uploader":{"login":"release-bot"},
                   "browser_download_url":"https://github.com/dev-marlon-mc/BlockProtect/releases/download/v26.2/BlockProtect-Audit-26.2.jar"},
                  {"url":"https://api.github.com/repos/dev-marlon-mc/BlockProtect/releases/assets/102",
                   "id":102,"node_id":"RA_kwDOExample2","name":"BlockProtect-Audit-26.2.jar.sha256",
                   "label":null,"uploader":{"login":"release-bot"},
                   "browser_download_url":"https://github.com/dev-marlon-mc/BlockProtect/releases/download/v26.2/BlockProtect-Audit-26.2.jar.sha256"}
                ]}
                """;

        List<GitHubReleaseUpdateSource.Asset> assets = GitHubReleaseUpdateSource.assets(json);

        assertEquals(2, assets.size());
        assertEquals("BlockProtect-Audit-26.2.jar", assets.get(0).name());
        assertEquals(URI.create("https://api.github.com/repos/dev-marlon-mc/BlockProtect/releases/assets/101"),
                assets.get(0).uri());
    }

    @Test
    void onlyTrustsAssetApiUrlsForTheConfiguredRepository() {
        assertTrue(GitHubReleaseUpdateSource.isApiAssetUri("dev-marlon-mc/BlockProtect",
                URI.create("https://api.github.com/repos/dev-marlon-mc/BlockProtect/releases/assets/101")));
        assertFalse(GitHubReleaseUpdateSource.isApiAssetUri("dev-marlon-mc/BlockProtect",
                URI.create("https://api.github.com/repos/someone/Other/releases/assets/101")));
        assertFalse(GitHubReleaseUpdateSource.isApiAssetUri("dev-marlon-mc/BlockProtect",
                URI.create("https://github.com/dev-marlon-mc/BlockProtect/releases/download/v26.2/module.jar")));
    }
}
