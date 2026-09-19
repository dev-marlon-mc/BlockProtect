package de.blockprotect.module.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChecksumVerifier {
    private static final Pattern HASH = Pattern.compile("^([0-9a-fA-F]{64})(?:\\s+[*]?[^\\s]+)?$");

    private ChecksumVerifier() {
    }

    public static Path checksumPath(Path jar) {
        Path besideJar = jar.resolveSibling(jar.getFileName() + ".sha256");
        if (Files.isRegularFile(besideJar)) {
            return besideJar;
        }
        String name = jar.getFileName().toString();
        if (name.endsWith(".jar")) {
            Path besideName = jar.resolveSibling(name.substring(0, name.length() - 4) + ".sha256");
            if (Files.isRegularFile(besideName)) {
                return besideName;
            }
        }
        return null;
    }

    public static String verify(Path jar, Path checksumFile) throws IOException {
        if (!Files.isRegularFile(jar) || Files.isSymbolicLink(jar)) {
            throw new IOException("Update ist keine reguläre Datei: " + jar);
        }
        if (checksumFile == null || !Files.isRegularFile(checksumFile) || Files.isSymbolicLink(checksumFile)) {
            throw new IOException("SHA-256-Datei fehlt für " + jar.getFileName());
        }
        String expected = parseExpected(Files.readString(checksumFile));
        String actual = sha256(jar);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("SHA-256-Prüfung fehlgeschlagen für " + jar.getFileName()
                    + " (erwartet " + expected + ", gefunden " + actual + ")");
        }
        return actual.toLowerCase(Locale.ROOT);
    }

    public static String parseExpected(String content) throws IOException {
        String normalized = content == null ? "" : content.trim();
        Matcher matcher = HASH.matcher(normalized);
        if (!matcher.matches()) {
            throw new IOException("SHA-256-Datei enthält keinen gültigen 64-stelligen Hash");
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 ist in dieser JVM nicht verfügbar", impossible);
        }
    }
}
