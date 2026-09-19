package de.blockprotect.module.internal;

import de.blockprotect.BlockProtectPlugin;
import de.blockprotect.audit.AuditRecorder;
import de.blockprotect.audit.InspectionState;
import de.blockprotect.config.ConfigService;
import de.blockprotect.module.BlockProtectModule;
import de.blockprotect.module.ModuleContext;
import de.blockprotect.module.ModuleSnapshot;
import de.blockprotect.module.ModuleStatus;
import de.blockprotect.module.ModuleUpdateResult;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts, stops, replaces and rolls back module class loaders. All lifecycle
 * mutations happen on the server thread; only release I/O runs asynchronously.
 */
public final class ModuleManager implements AutoCloseable {
    private final BlockProtectPlugin plugin;
    private final ConfigService config;
    private final AuditRecorder recorder;
    private final InspectionState inspectionState;
    private final Logger logger;
    private final Path modulesDirectory;
    private final Path updatesDirectory;
    private final Path backupsDirectory;
    private final ModuleCommandRegistry commands = new ModuleCommandRegistry();
    private final Map<String, ModuleRecord> records = new HashMap<>();
    private final Set<String> updating = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ModuleManager(BlockProtectPlugin plugin,
                         ConfigService config,
                         AuditRecorder recorder,
                         InspectionState inspectionState) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.inspectionState = Objects.requireNonNull(inspectionState, "inspectionState");
        this.logger = plugin.getLogger();
        Path data = plugin.getDataFolder().toPath();
        this.modulesDirectory = data.resolve("modules").normalize();
        String configuredUpdateDirectory = config.getString("updates.local-directory", "updates");
        Path candidateUpdateDirectory = data.resolve(configuredUpdateDirectory).normalize();
        if (!candidateUpdateDirectory.startsWith(data.normalize())) {
            logger.warning("updates.local-directory liegt außerhalb des Plugin-Ordners; verwende updates/.");
            candidateUpdateDirectory = data.resolve("updates").normalize();
        }
        this.updatesDirectory = candidateUpdateDirectory;
        this.backupsDirectory = data.resolve("module-backups").normalize();
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("ModuleManager ist bereits beendet");
        }
        try {
            Files.createDirectories(modulesDirectory);
            Files.createDirectories(updatesDirectory);
            Files.createDirectories(backupsDirectory);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Modul-Verzeichnisse konnten nicht angelegt werden", exception);
            return;
        }

        try (var paths = Files.list(modulesDirectory)) {
            paths.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(this::discover);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Modul-Verzeichnis konnte nicht gelesen werden", exception);
            return;
        }

        List<ModuleRecord> initial = new ArrayList<>(records.values());
        initial.sort(Comparator.comparing(record -> record.descriptor.id()));
        for (ModuleRecord record : initial) {
            if (!config.getBoolean("modules.auto-enable", true)
                    || isConfiguredDisabled(record.descriptor.id())) {
                record.status = ModuleStatus.DISABLED;
                record.message = config.getBoolean("modules.auto-enable", true)
                        ? "per Konfiguration deaktiviert" : "automatisches Aktivieren deaktiviert";
                continue;
            }
            enableRecord(record);
        }
        if (records.isEmpty()) {
            logger.warning("Keine Live-Module in " + modulesDirectory + " gefunden.");
        }
    }

    private synchronized void discover(Path path) {
        try {
            ModuleDescriptor descriptor = ModuleDescriptor.fromJar(path);
            if (records.containsKey(descriptor.id())) {
                logger.warning("Doppeltes Modul " + descriptor.id() + " ignoriert: " + path.getFileName());
                return;
            }
            records.put(descriptor.id(), new ModuleRecord(path.toAbsolutePath().normalize(), descriptor));
            logger.info("Modul gefunden: " + descriptor.id() + " v" + descriptor.version());
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Modul-JAR ignoriert: " + path.getFileName(), exception);
        }
    }

    public synchronized List<ModuleSnapshot> snapshots() {
        return records.values().stream()
                .sorted(Comparator.comparing(record -> record.descriptor.id()))
                .map(ModuleRecord::snapshot)
                .toList();
    }

    public ModuleCommandRegistry commands() {
        return commands;
    }

    public synchronized boolean enable(String id) {
        ModuleRecord record = records.get(normalizeId(id));
        if (record == null) {
            return false;
        }
        return enableRecord(record);
    }

    public synchronized boolean disable(String id) {
        ModuleRecord record = records.get(normalizeId(id));
        if (record == null) {
            return false;
        }
        disableRecord(record);
        return true;
    }

    public synchronized boolean restart(String id) {
        ModuleRecord record = records.get(normalizeId(id));
        if (record == null) {
            return false;
        }
        boolean wasEnabled = record.enabled;
        disableRecord(record);
        return !wasEnabled || enableRecord(record);
    }

    public void checkForUpdatesAsync(Consumer<List<UpdateNotice>> callback) {
        ModuleUpdateSource source;
        List<ModuleRecord> current;
        synchronized (this) {
            if (closed) {
                return;
            }
            try {
                source = updateSource();
            } catch (RuntimeException exception) {
                logger.warning("Updatequelle ist ungültig: " + exception.getMessage());
                if (callback != null) {
                    runOnServerThread(() -> callback.accept(List.of()));
                }
                return;
            }
            current = new ArrayList<>(records.values());
        }
        CompletableFuture.supplyAsync(() -> {
            List<UpdateNotice> notices = new ArrayList<>();
            for (ModuleRecord record : current) {
                try {
                    Optional<UpdateDescriptor> update = source.findLatest(record.descriptor);
                    synchronized (this) {
                        record.availableUpdate = update.map(value -> value.version().text()).orElse(null);
                    }
                    update.ifPresent(value -> notices.add(new UpdateNotice(record.descriptor.id(), value.version(), value.source())));
                } catch (Exception exception) {
                    logger.warning("Updateprüfung für " + record.descriptor.id() + " fehlgeschlagen: "
                            + exception.getMessage());
                }
            }
            return notices;
        }).thenAccept(notices -> runOnServerThread(() -> {
            if (callback != null && !closed) {
                callback.accept(notices);
            }
        }));
    }

    public void updateAsync(String id, Consumer<ModuleUpdateResult> callback) {
        String normalized = normalizeId(id);
        ModuleDescriptor installed;
        synchronized (this) {
            ModuleRecord record = records.get(normalized);
            if (record == null) {
                finish(callback, new ModuleUpdateResult(normalized, false, "Modul nicht gefunden"));
                return;
            }
            if (!updating.add(normalized)) {
                finish(callback, new ModuleUpdateResult(normalized, false, "Update läuft bereits"));
                return;
            }
            installed = record.descriptor;
        }

        CompletableFuture.supplyAsync(() -> prepareUpdate(normalized, installed))
                .whenComplete((prepared, throwable) -> runOnServerThread(() -> {
                    ModuleUpdateResult result;
                    if (throwable != null) {
                        result = new ModuleUpdateResult(normalized, false, rootMessage(throwable));
                    } else {
                        result = applyUpdate(prepared);
                    }
                    updating.remove(normalized);
                    if (callback != null && !closed) {
                        callback.accept(result);
                    }
                }));
    }

    private PreparedUpdate prepareUpdate(String id, ModuleDescriptor installed) {
        try {
            ModuleUpdateSource source = updateSource();
            UpdateDescriptor update = source.findLatest(installed)
                    .orElseThrow(() -> new IOException("Keine neuere, geprüfte Version verfügbar"));
            Path candidate = source.download(update).toAbsolutePath().normalize();
            if (!candidate.startsWith(updatesDirectory.toAbsolutePath().normalize())) {
                throw new IOException("Update liegt außerhalb des sicheren Update-Verzeichnisses");
            }
            String hash = ChecksumVerifier.verify(candidate, ChecksumVerifier.checksumPath(candidate));
            ModuleDescriptor descriptor = ModuleDescriptor.fromJar(candidate);
            if (!descriptor.id().equals(id)) {
                throw new IOException("Update-ID stimmt nicht überein: " + descriptor.id());
            }
            if (descriptor.version().compareTo(installed.version()) <= 0) {
                throw new IOException("Update-Version ist nicht neuer als " + installed.version());
            }
            CompatibilityChecker.requireCompatible(plugin, descriptor);
            return new PreparedUpdate(id, candidate, descriptor, hash);
        } catch (Exception exception) {
            throw new RuntimeException(exception.getMessage(), exception);
        }
    }

    private synchronized ModuleUpdateResult applyUpdate(PreparedUpdate update) {
        ModuleRecord record = records.get(update.id());
        if (record == null) {
            return new ModuleUpdateResult(update.id(), false, "Modul wurde inzwischen entfernt");
        }
        ModuleDescriptor oldDescriptor = record.descriptor;
        Path oldPath = record.path;
        boolean wasEnabled = record.enabled;
        Path backup = null;
        record.status = ModuleStatus.UPDATING;
        try {
            if (wasEnabled) {
                disableRecord(record);
            }
            if (Files.exists(oldPath)) {
                Files.createDirectories(backupsDirectory.resolve(record.descriptor.id()));
                backup = backupsDirectory.resolve(record.descriptor.id())
                        .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + "-" + oldPath.getFileName());
                move(oldPath, backup);
            }
            Path temporary = modulesDirectory.resolve(update.id() + ".jar.new");
            Files.copy(update.candidate(), temporary, StandardCopyOption.REPLACE_EXISTING);
            move(temporary, modulesDirectory.resolve(update.id() + ".jar"));
            record.path = modulesDirectory.resolve(update.id() + ".jar").toAbsolutePath().normalize();
            record.descriptor = update.descriptor;
            record.message = "SHA-256 " + update.sha256();
            if (wasEnabled) {
                if (!enableRecord(record)) {
                    throw new IllegalStateException("Neue Version konnte nicht aktiviert werden");
                }
            } else {
                record.status = ModuleStatus.DISABLED;
            }
            record.availableUpdate = null;
            logger.info("Modul " + update.id() + " erfolgreich auf v" + update.descriptor.version() + " aktualisiert.");
            return new ModuleUpdateResult(update.id(), true, "Aktualisiert auf v" + update.descriptor.version());
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Update für Modul " + update.id() + " fehlgeschlagen; Rollback wird versucht", exception);
            rollback(record, oldDescriptor, oldPath, backup, wasEnabled);
            return new ModuleUpdateResult(update.id(), false, "Update fehlgeschlagen, Rollback ausgeführt: "
                    + rootMessage(exception));
        }
    }

    private void rollback(ModuleRecord record,
                           ModuleDescriptor oldDescriptor,
                           Path oldPath,
                           Path backup,
                           boolean wasEnabled) {
        if (record.loaded != null) {
            disableRecord(record);
        }
        try {
            Path installed = modulesDirectory.resolve(record.descriptor.id() + ".jar");
            Files.deleteIfExists(installed);
            if (backup != null && Files.exists(backup)) {
                move(backup, oldPath);
            }
            record.path = oldPath;
            record.descriptor = oldDescriptor;
            record.status = ModuleStatus.DISABLED;
            record.message = "Rollback nach fehlgeschlagenem Update";
            if (wasEnabled && !enableRecord(record)) {
                record.status = ModuleStatus.FAILED;
                record.message = "Rollback-Datei konnte nicht aktiviert werden";
            }
        } catch (Exception rollbackFailure) {
            record.status = ModuleStatus.FAILED;
            record.message = "Rollback fehlgeschlagen: " + rootMessage(rollbackFailure);
            logger.log(Level.SEVERE, "Rollback für " + record.descriptor.id() + " fehlgeschlagen", rollbackFailure);
        }
    }

    private synchronized boolean enableRecord(ModuleRecord record) {
        if (closed) {
            return false;
        }
        if (record.enabled) {
            return true;
        }
        record.status = ModuleStatus.UPDATING;
        record.message = "wird aktiviert";
        ModuleClassLoader loader = null;
        ModuleResourceRegistry resources = null;
        try {
            CompatibilityChecker.requireCompatible(plugin, record.descriptor);
            loader = new ModuleClassLoader(record.path.toUri().toURL(), plugin.getClass().getClassLoader());
            Class<?> type = Class.forName(record.descriptor.entrypoint(), true, loader);
            if (!BlockProtectModule.class.isAssignableFrom(type)) {
                throw new IllegalStateException("Entrypoint implementiert BlockProtectModule nicht");
            }
            BlockProtectModule entrypoint = (BlockProtectModule) type.getDeclaredConstructor().newInstance();
            Path dataDirectory = plugin.getDataFolder().toPath().resolve("module-data").resolve(record.descriptor.id());
            Files.createDirectories(dataDirectory);
            resources = new ModuleResourceRegistry(plugin, logger);
            ModuleContext context = new ModuleContext(plugin, record.descriptor.id(), dataDirectory, config, recorder,
                    resources, commands, Map.of(InspectionState.class, inspectionState));
            Loaded loaded = new Loaded(loader, resources, entrypoint, context);
            record.loaded = loaded;
            withContextClassLoader(loader, entrypoint::enable, context);
            record.enabled = true;
            record.status = ModuleStatus.ENABLED;
            record.message = "aktiv";
            logger.info("Modul aktiviert: " + record.descriptor.id() + " v" + record.descriptor.version());
            return true;
        } catch (Exception exception) {
            record.enabled = false;
            record.status = exception.getMessage() != null && exception.getMessage().contains("Paper-API")
                    ? ModuleStatus.INCOMPATIBLE : ModuleStatus.FAILED;
            record.message = rootMessage(exception);
            if (resources != null) {
                resources.close();
            }
            closeLoader(loader);
            record.loaded = null;
            logger.log(Level.SEVERE, "Modul " + record.descriptor.id() + " konnte nicht aktiviert werden", exception);
            return false;
        }
    }

    private synchronized void disableRecord(ModuleRecord record) {
        Loaded loaded = record.loaded;
        if (loaded == null) {
            record.enabled = false;
            if (record.status != ModuleStatus.FAILED && record.status != ModuleStatus.INCOMPATIBLE) {
                record.status = ModuleStatus.DISABLED;
            }
            return;
        }
        record.status = ModuleStatus.UPDATING;
        try {
            withContextClassLoader(loaded.loader, loaded.entrypoint::disable, loaded.context);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Modul " + record.descriptor.id() + " meldete Fehler beim Stoppen", exception);
        } finally {
            loaded.resources.close();
            closeLoader(loaded.loader);
            record.loaded = null;
            record.enabled = false;
            record.status = ModuleStatus.DISABLED;
            record.message = "deaktiviert";
        }
        logger.info("Modul deaktiviert: " + record.descriptor.id());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        records.values().stream()
                .sorted(Comparator.comparing(record -> record.descriptor.id()))
                .forEach(this::disableRecord);
        records.clear();
    }

    private ModuleUpdateSource updateSource() {
        String source = config.getString("updates.source", "local").toLowerCase(java.util.Locale.ROOT);
        if (source.equals("github")) {
            return new GitHubReleaseUpdateSource(
                    config.getString("updates.github.repository", ""), updatesDirectory, logger);
        }
        if (!source.equals("local")) {
            throw new IllegalArgumentException("Unbekannte Updatequelle: " + source);
        }
        return new LocalModuleUpdateSource(updatesDirectory, logger);
    }

    private boolean isConfiguredDisabled(String id) {
        return config.getStringList("modules.disabled").stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(id::equals);
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void runOnServerThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeLoader(ModuleClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException exception) {
            // The module is already detached; there is no safe recovery action.
        }
    }

    private static void withContextClassLoader(ModuleClassLoader loader,
                                               ThrowingConsumer<ModuleContext> action,
                                               ModuleContext context) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            action.accept(context);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void withContextClassLoader(ModuleClassLoader loader,
                                               ThrowingRunnable action,
                                               ModuleContext context) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            action.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private <T> void finish(Consumer<T> callback, T result) {
        if (callback != null) {
            runOnServerThread(() -> callback.accept(result));
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    public record UpdateNotice(String moduleId, ModuleVersion version, String source) {
    }

    private record PreparedUpdate(String id, Path candidate, ModuleDescriptor descriptor, String sha256) {
    }

    private final class ModuleRecord {
        private Path path;
        private ModuleDescriptor descriptor;
        private boolean enabled;
        private ModuleStatus status = ModuleStatus.DISABLED;
        private String availableUpdate;
        private String message = "nicht aktiviert";
        private Loaded loaded;

        private ModuleRecord(Path path, ModuleDescriptor descriptor) {
            this.path = path;
            this.descriptor = descriptor;
        }

        private ModuleSnapshot snapshot() {
            return new ModuleSnapshot(descriptor.id(), descriptor.name(), descriptor.version().text(), enabled,
                    status, availableUpdate, message);
        }
    }

    private record Loaded(ModuleClassLoader loader,
                          ModuleResourceRegistry resources,
                          BlockProtectModule entrypoint,
                          ModuleContext context) {
    }
}
