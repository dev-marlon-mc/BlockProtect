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
            "\\\"url\\\"\\s*:\\s*\\\"(https://api\\.github\\.com/repos/[^\\\"]+/releases/assets/[0-9]+)\\\".*?"
                    + "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL);
    private static final String API_HOST = "api.github.com";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "BLOCKPROTECT_GITHUB_TOKEN";

    private final String repository;
    private final Path updateDirectory;
    private final Logger logger;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GitHubReleaseUpdateSource(String repository, Path updateDirectory, Logger logger) {
        this(repository, updateDirectory, logger, System.getenv(TOKEN_ENVIRONMENT_VARIABLE));
    }

    GitHubReleaseUpdateSource(String repository, Path updateDirectory, Logger logger, String token) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("updates.github.repository muss owner/repository sein");
        }
        this.repository = repository;
        this.updateDirectory = updateDirectory.toAbsolutePath().normalize();
        this.logger = logger;
        this.token = token == null || token.isBlank() ? null : token.trim();
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
        if (!isApiAssetUri(repository, jar.uri()) || !isApiAssetUri(repository, checksum.uri())) {
            throw new IOException("GitHub-Release enthält eine unsichere Asset-API-URL");
        }
        return Optional.of(new UpdateDescriptor(installed.id(), version,
                "GitHub Release " + tagMatcher.group(1), null,
                jar.uri(), checksum.uri(), jar.name(), checksum.name()));
    }

    static boolean isModuleJarAsset(String moduleId, String assetName) {
        String normalizedName = assetName.toLowerCase(Locale.ROOT);
        if (normalizedName.startsWith("blockprotect-")) {
            normalizedName = normalizedName.substring("blockprotect-".length());
        }
        return normalizedName.startsWith(moduleId.toLowerCase(Locale.ROOT) + "-")
                && normalizedName.endsWith(".jar");
    }

    static boolean isApiAssetUri(String repository, URI uri) {
        if (repository == null || uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || !API_HOST.equalsIgnoreCase(uri.getHost()) || uri.getQuery() != null || uri.getFragment() != null) {
            return false;
        }
        String prefix = "/repos/" + repository + "/releases/assets/";
        String path = uri.getPath();
        return path != null && path.startsWith(prefix)
                && path.substring(prefix.length()).matches("[0-9]+");
    }

    @Override
    public Path download(UpdateDescriptor update) throws Exception {
        Files.createDirectories(updateDirectory);
        String fileName = update.jarFileName();
        String checksumFileName = update.checksumFileName();
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+\\.jar")
                || checksumFileName == null || !checksumFileName.matches("[A-Za-z0-9._-]+\\.sha256")
                || !(checksumFileName.equals(fileName + ".sha256")
                || checksumFileName.equals(fileName.replaceAll("\\.jar$", ".sha256")))) {
            throw new IOException("Unsicherer GitHub-Assetname");
        }
        Path jar = updateDirectory.resolve(fileName).normalize();
        if (!jar.getParent().equals(updateDirectory.toAbsolutePath().normalize())) {
            throw new IOException("GitHub-Asset liegt außerhalb des Update-Verzeichnisses");
        }
        Path temporaryJar = updateDirectory.resolve("." + fileName + ".download");
        Path checksumTarget = updateDirectory.resolve(checksumFileName);
        Path temporaryChecksum = updateDirectory.resolve("." + checksumFileName + ".download");
        download(update.jarUri(), temporaryJar);
        download(update.checksumUri(), temporaryChecksum);
        ChecksumVerifier.verify(temporaryJar, temporaryChecksum);
        Files.move(temporaryJar, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(temporaryChecksum, checksumTarget, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        logger.info("GitHub-Release heruntergeladen: " + jar.getFileName());
        return jar;
    }

    private void download(URI uri, Path target) throws Exception {
        if (!isApiAssetUri(repository, uri)) {
            throw new IOException("Unsichere GitHub-Download-URL");
        }
        URI current = uri;
        for (int redirects = 0; redirects <= 3; redirects++) {
            boolean apiAsset = isApiAssetUri(repository, current);
            if (!apiAsset && !allowedRedirectUri(current)) {
                throw new IOException("GitHub leitete auf einen nicht erlaubten Download-Host um");
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "BlockProtect-Core")
                    .GET();
            if (apiAsset && token != null) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (isRedirect(response.statusCode())) {
                if (redirects == 3) {
                    throw new IOException("Zu viele Weiterleitungen beim GitHub-Asset-Download");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("GitHub-Weiterleitung ohne Location-Header"));
                current = current.resolve(location);
                if (!allowedRedirectUri(current)) {
                    throw new IOException("GitHub leitete auf einen nicht erlaubten Download-Host um");
                }
                continue;
            }
            if (response.statusCode() / 100 != 2) {
                throw new IOException("GitHub antwortete mit HTTP " + response.statusCode()
                        + " für " + current.getHost()
                        + (token == null ? " (bei privatem Repository BLOCKPROTECT_GITHUB_TOKEN setzen)" : ""));
            }
            Files.write(target, response.body());
            return;
        }
        throw new IOException("GitHub-Asset konnte nicht heruntergeladen werden");
    }

    private String request(URI uri, String host) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BlockProtect-Core")
                .headers(token == null ? new String[0] : new String[]{"Authorization", "Bearer " + token})
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub antwortete mit HTTP " + response.statusCode() + " für " + host);
        }
        return response.body();
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private static boolean allowedRedirectUri(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && (host.equalsIgnoreCase("objects.githubusercontent.com")
                || host.equalsIgnoreCase("release-assets.githubusercontent.com"));
    }

    static List<Asset> assets(String json) {
        List<Asset> assets = new ArrayList<>();
        Matcher matcher = ASSET.matcher(json);
        while (matcher.find()) {
            assets.add(new Asset(unescape(matcher.group(2)), URI.create(unescape(matcher.group(1)))));
        }
        return assets;
    }

    private static String unescape(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"");
    }

    record Asset(String name, URI uri) {
    }
}
