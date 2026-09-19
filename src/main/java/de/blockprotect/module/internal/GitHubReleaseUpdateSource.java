package de.blockprotect.module.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/** Uses only GitHub's immutable latest-release endpoint, never branches/commits. */
public final class GitHubReleaseUpdateSource implements ModuleUpdateSource {
    private static final Pattern TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ASSET = Pattern.compile(
            "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                    + "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL);
    private static final String API_HOST = "api.github.com";

    private final String repository;
    private final Path updateDirectory;
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public GitHubReleaseUpdateSource(String repository, Path updateDirectory, Logger logger) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("updates.github.repository muss owner/repository sein");
        }
        this.repository = repository;
        this.updateDirectory = updateDirectory;
        this.logger = logger;
    }

    @Override
    public Optional<UpdateDescriptor> findLatest(ModuleDescriptor installed) throws Exception {
        URI endpoint = URI.create("https://" + API_HOST + "/repos/" + repository + "/releases/latest");
        String json = request(endpoint, API_HOST);
        Matcher tagMatcher = TAG.matcher(json);
        if (!tagMatcher.find()) {
            throw new IOException("GitHub-Release enthält keinen tag_name");
        }
        ModuleVersion version;
        try {
            version = ModuleVersion.parse(tagMatcher.group(1));
        } catch (IllegalArgumentException noVersion) {
            throw new IOException("GitHub-Release-Tag ist keine numerische Version: " + tagMatcher.group(1), noVersion);
        }
        if (version.compareTo(installed.version()) <= 0) {
            return Optional.empty();
        }

        List<Asset> assets = assets(json);
        Asset jar = assets.stream()
                .filter(asset -> isModuleJarAsset(installed.id(), asset.name()))
                .findFirst()
                .orElseThrow(() -> new IOException("Kein Modul-JAR für " + installed.id() + " im Release gefunden"));
        Asset checksum = assets.stream()
                .filter(asset -> asset.name().equals(jar.name() + ".sha256")
                        || asset.name().equals(jar.name().replaceAll("\\.jar$", ".sha256")))
                .findFirst()
                .orElseThrow(() -> new IOException("Kein SHA-256-Asset für " + jar.name() + " gefunden"));
        return Optional.of(new UpdateDescriptor(installed.id(), version,
                "GitHub Release " + tagMatcher.group(1), null,
                URI.create(jar.url()), URI.create(checksum.url())));
    }

    static boolean isModuleJarAsset(String moduleId, String assetName) {
        String normalizedName = assetName.toLowerCase(Locale.ROOT);
        if (normalizedName.startsWith("blockprotect-")) {
            normalizedName = normalizedName.substring("blockprotect-".length());
        }
        return normalizedName.startsWith(moduleId.toLowerCase(Locale.ROOT) + "-")
                && normalizedName.endsWith(".jar");
    }

    @Override
    public Path download(UpdateDescriptor update) throws Exception {
        Files.createDirectories(updateDirectory);
        String fileName = update.jarUri().getPath().substring(update.jarUri().getPath().lastIndexOf('/') + 1);
        if (!fileName.matches("[A-Za-z0-9._-]+\\.jar")) {
            throw new IOException("Unsicherer GitHub-Assetname");
        }
        Path jar = updateDirectory.resolve(fileName).normalize();
        if (!jar.getParent().equals(updateDirectory.toAbsolutePath().normalize())) {
            throw new IOException("GitHub-Asset liegt außerhalb des Update-Verzeichnisses");
        }
        Path temporaryJar = updateDirectory.resolve("." + fileName + ".download");
        Path temporaryChecksum = updateDirectory.resolve("." + fileName + ".sha256.download");
        download(update.jarUri(), temporaryJar);
        download(update.checksumUri(), temporaryChecksum);
        ChecksumVerifier.verify(temporaryJar, temporaryChecksum);
        Files.move(temporaryJar, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(temporaryChecksum, ChecksumVerifier.checksumPath(jar) == null
                ? jar.resolveSibling(jar.getFileName() + ".sha256")
                : ChecksumVerifier.checksumPath(jar),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.info("GitHub-Release heruntergeladen: " + jar.getFileName());
        return jar;
    }

    private void download(URI uri, Path target) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedDownloadHost(uri.getHost())) {
            throw new IOException("Unsichere GitHub-Download-URL: " + uri);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "BlockProtect-Core")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub antwortete mit HTTP " + response.statusCode() + " für " + uri.getHost());
        }
        Files.write(target, response.body());
    }

    private String request(URI uri, String host) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BlockProtect-Core")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub antwortete mit HTTP " + response.statusCode() + " für " + host);
        }
        return response.body();
    }

    private static boolean allowedDownloadHost(String host) {
        return host != null && (host.equalsIgnoreCase("github.com")
                || host.equalsIgnoreCase("objects.githubusercontent.com")
                || host.equalsIgnoreCase("release-assets.githubusercontent.com"));
    }

    private static List<Asset> assets(String json) {
        List<Asset> assets = new ArrayList<>();
        Matcher matcher = ASSET.matcher(json);
        while (matcher.find()) {
            assets.add(new Asset(unescape(matcher.group(1)), unescape(matcher.group(2))));
        }
        return assets;
    }

    private static String unescape(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"");
    }

    private record Asset(String name, String url) {
    }
}
