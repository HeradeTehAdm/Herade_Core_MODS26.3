package studio.herade.core;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerSessionTracker {
    public record Session(UUID playerId, String playerName, long joinedAtMillis, long lastSeenAtMillis, boolean online) {
    }

    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public void joined(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        sessions.put(playerId, new Session(playerId, playerName, now, now, true));
    }

    public void left(UUID playerId, String playerName) {
        if (playerId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        sessions.computeIfPresent(playerId, (id, previous) ->
                new Session(id,
                        playerName == null || playerName.isBlank() ? previous.playerName() : playerName,
                        previous.joinedAtMillis(),
                        now,
                        false));
    }

    public Optional<Session> find(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public int onlineCount() {
        return (int) sessions.values().stream().filter(Session::online).count();
    }
}
