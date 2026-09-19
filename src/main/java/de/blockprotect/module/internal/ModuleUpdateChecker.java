package de.blockprotect.module.internal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Core-owned 24-hour release checker; it never reloads the core plugin. */
public final class ModuleUpdateChecker implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ModuleManager manager;
    private final long intervalTicks;
    private BukkitTask task;

    public ModuleUpdateChecker(JavaPlugin plugin, ModuleManager manager, int intervalHours) {
        this.plugin = plugin;
        this.manager = manager;
        long hours = Math.max(1, intervalHours);
        this.intervalTicks = Math.multiplyExact(hours, 60L * 60L * 20L);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () ->
                manager.checkForUpdatesAsync(notices -> notices.forEach(notice -> plugin.getLogger().info(
                        "Neues Modul-Release gefunden: " + notice.moduleId() + " v" + notice.version()
                                + " (" + notice.source() + "). Nutze /blockprotect module update "
                                + notice.moduleId() + "."))), 40L, intervalTicks);
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
