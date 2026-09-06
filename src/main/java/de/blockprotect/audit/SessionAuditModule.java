package de.blockprotect.audit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class SessionAuditModule implements AuditModule {
    private final AuditRecorder recorder;

    public SessionAuditModule(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String id() {
        return "sessions";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onJoin(PlayerJoinEvent event) {
        if (!recorder.option("tracking.options.log-sessions", true)) {
            return;
        }
        record(event.getPlayer(), "PLAYER_JOIN", event.getJoinMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        if (!recorder.option("tracking.options.log-sessions", true)) {
            return;
        }
        record(event.getPlayer(), "PLAYER_QUIT", event.getQuitMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onKick(PlayerKickEvent event) {
        if (!recorder.option("tracking.options.log-sessions", true)) {
            return;
        }
        record(event.getPlayer(), "PLAYER_KICK", event.getReason());
    }

    private void record(Player player, String action, String details) {
        recorder.record(id(), null, AuditRecorder.at(
                player.getUniqueId(), player.getName(), id(), action, player.getLocation(),
                "minecraft:player", 0, null, null, null, AuditUtil.details("message", details)
        ));
    }

}
