package de.blockprotect;

import de.blockprotect.audit.AuditRecorder;
import de.blockprotect.audit.InspectionState;
import de.blockprotect.command.BlockProtectCommand;
import de.blockprotect.config.ConfigService;
import de.blockprotect.module.internal.ModuleAdminGui;
import de.blockprotect.module.internal.ModuleManager;
import de.blockprotect.module.internal.ModuleUpdateChecker;
import de.blockprotect.storage.AuditStore;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class BlockProtectPlugin extends JavaPlugin {
    private ConfigService configService;
    private AuditStore auditStore;
    private AuditRecorder recorder;
    private InspectionState inspectionState;
    private ModuleManager moduleManager;
    private ModuleUpdateChecker updateChecker;

    @Override
    public void onEnable() {
        configService = new ConfigService(this);
        configService.load();

        auditStore = new AuditStore(this, configService);
        try {
            auditStore.start();
        } catch (SQLException exception) {
            getLogger().severe("BlockProtect konnte die SQLite-Datenbank nicht öffnen: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        recorder = new AuditRecorder(this, configService, auditStore);
        inspectionState = new InspectionState();
        moduleManager = new ModuleManager(this, configService, recorder, inspectionState);
        moduleManager.start();
        ModuleAdminGui moduleGui = new ModuleAdminGui(this, moduleManager);
        BlockProtectCommand command = new BlockProtectCommand(
                this, configService, auditStore, inspectionState, moduleManager, moduleGui);
        PluginManager pluginManager = getServer().getPluginManager();

        if (getCommand("blockprotect") != null) {
            getCommand("blockprotect").setExecutor(command);
            getCommand("blockprotect").setTabCompleter(command);
        }
        pluginManager.registerEvents(command, this);
        pluginManager.registerEvents(moduleGui, this);

        updateChecker = new ModuleUpdateChecker(this, moduleManager,
                configService.getInt("updates.check-interval-hours", 24));
        if (configService.getBoolean("updates.enabled", true)) {
            updateChecker.start();
        }

        getLogger().info("BlockProtect-Core aktiviert: Live-Module ohne Bukkit-/Paper-Reload.");
        getLogger().info("Verwende /blockprotect module gui für die Modulverwaltung.");
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.close();
        }
        if (moduleManager != null) {
            moduleManager.close();
        }
        if (auditStore != null) {
            auditStore.close();
        }
    }

    public ConfigService configService() {
        return configService;
    }

    public AuditStore auditStore() {
        return auditStore;
    }

    public AuditRecorder recorder() {
        return recorder;
    }

    public InspectionState inspectionState() {
        return inspectionState;
    }

    public ModuleManager moduleManager() {
        return moduleManager;
    }
}
