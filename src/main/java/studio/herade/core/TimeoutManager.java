package studio.herade.core;

import studio.herade.core.moderation.Punishment;
import studio.herade.core.moderation.PunishmentStore;
import studio.herade.core.moderation.PunishmentType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TimeoutManager {
    private final Map<UUID, Long> expirations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private int ticksUntilPrune;

    public TimeoutManager(PunishmentStore store) {
        long now = System.currentTimeMillis();
        for (Punishment punishment : store.all()) {
            if (punishment.type() == PunishmentType.TIMEOUT && !punishment.isExpired(now)) {
                expirations.merge(punishment.targetId(), punishment.expiresAtMillis(), Math::max);
            }
        }
    }

    public void tick() {
        if (++ticksUntilPrune < 20) {
            return;
        }
        ticksUntilPrune = 0;
        pruneExpired();
    }

    public void pruneExpired() {
        long now = System.currentTimeMillis();
        expirations.entrySet().removeIf(entry -> entry.getValue() <= now);
        lastNotice.keySet().removeIf(playerId -> !expirations.containsKey(playerId));
    }

    public void apply(UUID playerId, long expiresAtMillis) {
        if (playerId == null || expiresAtMillis <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("A timeout needs a player and a future expiry.");
        }
        expirations.merge(playerId, expiresAtMillis, Math::max);
        lastNotice.remove(playerId);
    }

    public boolean clear(UUID playerId) {
        if (playerId == null) return false;
        lastNotice.remove(playerId);
        return expirations.remove(playerId) != null;
    }

    public boolean isTimedOut(UUID playerId) {
        if (playerId == null) return false;
        Long expiresAt = expirations.get(playerId);
        if (expiresAt == null) return false;
        if (System.currentTimeMillis() >= expiresAt) {
            expirations.remove(playerId, expiresAt);
            lastNotice.remove(playerId);
            return false;
        }
        return true;
    }

    public boolean shouldNotify(UUID playerId) {
        return shouldNotify(playerId, System.currentTimeMillis());
    }

    boolean shouldNotify(UUID playerId, long now) {
        if (playerId == null || !isTimedOut(playerId)) {
            return false;
        }
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        lastNotice.compute(playerId, (id, previous) -> {
            if (previous == null || now - previous >= 2000) {
                allowed.set(true);
                return now;
            }
            return previous;
        });
        return allowed.get();
    }
}
