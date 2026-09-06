package de.blockprotect;

import de.blockprotect.audit.AuditModule;
import de.blockprotect.audit.AuditRecorder;
import de.blockprotect.audit.BlockAuditModule;
import de.blockprotect.audit.ContainerAuditModule;
import de.blockprotect.audit.EntityAuditModule;
import de.blockprotect.audit.InteractionAuditModule;
import de.blockprotect.audit.InspectionState;
import de.blockprotect.audit.SessionAuditModule;
import de.blockprotect.command.BlockProtectCommand;
import de.blockprotect.config.ConfigService;
import de.blockprotect.storage.AuditStore;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

public final class BlockProtectPlugin extends JavaPlugin {
    private ConfigService configService;
    private AuditStore auditStore;
    private AuditRecorder recorder;

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
        InspectionState inspectionState = new InspectionState();
        BlockProtectCommand command = new BlockProtectCommand(this, configService, auditStore, inspectionState);
        PluginManager pluginManager = getServer().getPluginManager();
        List<AuditModule> modules = List.of(
                new BlockAuditModule(recorder),
                new ContainerAuditModule(recorder),
                new EntityAuditModule(recorder),
                new InteractionAuditModule(recorder, inspectionState),
                new SessionAuditModule(recorder)
        );
        for (AuditModule module : modules) {
            pluginManager.registerEvents(module, this);
        }

        if (getCommand("blockprotect") != null) {
            getCommand("blockprotect").setExecutor(command);
            getCommand("blockprotect").setTabCompleter(command);
        }
        pluginManager.registerEvents(command, this);

        getLogger().info("BlockProtect aktiviert: modulare Audit-Aufzeichnung mit SQLite und Live-Konfiguration.");
        getLogger().info("Verwende /blockprotect help für Lookup, Inspect und Ingame-Einstellungen.");
    }

    @Override
    public void onDisable() {
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

    /**
     * Allows another plugin to add an independent audit module without changing
     * BlockProtect's core listeners. The module can gate itself with the live
     * ConfigService snapshot and submit records through AuditRecorder.
     */
    public void registerModule(AuditModule module) {
        if (module == null) {
            throw new IllegalArgumentException("module darf nicht null sein");
        }
        getServer().getPluginManager().registerEvents(module, this);
    }
}
