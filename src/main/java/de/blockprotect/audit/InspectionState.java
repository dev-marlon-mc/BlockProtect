package de.blockprotect.audit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectionState {
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();

    public boolean toggle(UUID playerId) {
        if (activePlayers.remove(playerId)) {
            return false;
        }
        activePlayers.add(playerId);
        return true;
    }

    public boolean isActive(UUID playerId) {
        return activePlayers.contains(playerId);
    }

    public void deactivate(UUID playerId) {
        activePlayers.remove(playerId);
    }
}

