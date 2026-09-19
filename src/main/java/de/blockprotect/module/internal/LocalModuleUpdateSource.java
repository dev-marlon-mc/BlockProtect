package de.blockprotect.module.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

public final class LocalModuleUpdateSource implements ModuleUpdateSource {
    private final Path updateDirectory;
    private final Logger logger;

    public LocalModuleUpdateSource(Path updateDirectory, Logger logger) {
        this.updateDirectory = updateDirectory;
        this.logger = logger;
    }

    @Override
    public Optional<UpdateDescriptor> findLatest(ModuleDescriptor installed) throws IOException {
        if (!Files.isDirectory(updateDirectory)) {
            return Optional.empty();
        }
        try (var files = Files.list(updateDirectory)) {
            return files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> candidate(installed, path))
                    .flatMap(Optional::stream)
                    .filter(candidate -> candidate.version().compareTo(installed.version()) > 0)
                    .max(Comparator.comparing(UpdateDescriptor::version));
        }
    }

    private Optional<UpdateDescriptor> candidate(ModuleDescriptor installed, Path path) {
        try {
            ModuleDescriptor descriptor = ModuleDescriptor.fromJar(path);
            if (!descriptor.id().equals(installed.id())) {
                return Optional.empty();
            }
            ChecksumVerifier.verify(path, ChecksumVerifier.checksumPath(path));
            return Optional.of(new UpdateDescriptor(
                    descriptor.id(), descriptor.version(), "lokales Update-Verzeichnis", path, null, null, null, null));
        } catch (Exception exception) {
            logger.warning("Lokales Modul-Update ignoriert (" + path.getFileName() + "): " + exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Path download(UpdateDescriptor update) {
        if (update.localPath() == null) {
            throw new IllegalArgumentException("Lokales Update ohne Dateipfad");
        }
        return update.localPath();
    }
}
