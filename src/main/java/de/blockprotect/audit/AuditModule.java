package de.blockprotect.audit;

import org.bukkit.event.Listener;

public interface AuditModule extends Listener {
    String id();
}

