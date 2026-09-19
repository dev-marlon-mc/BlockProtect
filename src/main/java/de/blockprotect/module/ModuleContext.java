package de.blockprotect.module;

import de.blockprotect.audit.AuditRecorder;
import de.blockprotect.config.ConfigService;
import de.blockprotect.module.internal.ModuleCommandRegistry;
import de.blockprotect.module.internal.ModuleResourceRegistry;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Narrow service boundary exposed to a live module. Modules should never call
 * Bukkit's global scheduler or register listeners directly; the context keeps
 * all resources attributable to one class loader.
 */
public final class ModuleContext {
    private final JavaPlugin plugin;
    private final String moduleId;
    private final Path dataDirectory;
    private final ConfigService config;
    private final AuditRecorder recorder;
    private final ModuleResourceRegistry resources;
    private final ModuleCommandRegistry commands;
    private final Map<Class<?>, Object> services;

    public ModuleContext(JavaPlugin plugin,
                         String moduleId,
                         Path dataDirectory,
                         ConfigService config,
                         AuditRecorder recorder,
                         ModuleResourceRegistry resources,
                         ModuleCommandRegistry commands,
                         Map<Class<?>, Object> services) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.config = Objects.requireNonNull(config, "config");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.services = Map.copyOf(services);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public String moduleId() {
        return moduleId;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    public ConfigService config() {
        return config;
    }

    public AuditRecorder recorder() {
        return recorder;
    }

    public boolean isActive() {
        return resources.isActive();
    }

    public <T> T service(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalArgumentException("Kein Core-Service für " + type.getName());
        }
        return type.cast(service);
    }

    public void registerListener(Listener listener) {
        resources.registerListener(listener);
    }

    public BukkitTask runTask(Runnable task) {
        return resources.trackTask(Bukkit.getScheduler().runTask(plugin, resources.guard(task)));
    }

    public BukkitTask runTaskLater(Runnable task, long delayTicks) {
        return resources.trackTask(Bukkit.getScheduler().runTaskLater(plugin, resources.guard(task), delayTicks));
    }

    public BukkitTask runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        return resources.trackTask(Bukkit.getScheduler().runTaskTimer(plugin, resources.guard(task), delayTicks, periodTicks));
    }

    public BukkitTask runTaskAsync(Runnable task) {
        return resources.trackTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, resources.guard(task)));
    }

    public BukkitTask runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        return resources.trackTask(Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, resources.guard(task), delayTicks, periodTicks));
    }

    public void registerCloseable(AutoCloseable closeable) {
        resources.registerCloseable(closeable);
    }

    public void registerExecutor(ExecutorService executor) {
        resources.registerCloseable(executor::shutdownNow);
    }

    public void registerThread(Thread thread) {
        resources.registerThread(thread);
    }

    public void registerCommand(String name, String permission, ModuleCommand command) {
        resources.registerCloseable(commands.register(moduleId, name, permission, command));
    }
}
