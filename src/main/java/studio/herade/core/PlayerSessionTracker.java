package studio.herade.core;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerSessionTracker {
    public record Session(UUID playerId, String playerName, long joinedAtMillis, long lastSeenAtMillis, boolean online) {
    }

    private static final int MAX_TRACKED_SESSIONS = 10_000;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public void joined(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        sessions.put(playerId, new Session(playerId, playerName, now, now, true));
        trimIfNeeded();
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
        trimIfNeeded();
    }

    public Optional<Session> find(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    private void trimIfNeeded() {
        while (sessions.size() > MAX_TRACKED_SESSIONS) {
            sessions.values().stream()
                    .filter(session -> !session.online())
                    .min(java.util.Comparator.comparingLong(Session::lastSeenAtMillis))
                    .ifPresent(oldest -> sessions.remove(oldest.playerId(), oldest));
            if (sessions.size() > MAX_TRACKED_SESSIONS &&
                    sessions.values().stream().allMatch(Session::online)) {
                break;
            }
        }
    }

    public int onlineCount() {
        return (int) sessions.values().stream().filter(Session::online).count();
    }
}
