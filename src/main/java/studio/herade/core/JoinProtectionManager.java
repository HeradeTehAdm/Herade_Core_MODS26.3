package studio.herade.core;

import java.util.ArrayDeque;
import java.util.UUID;

public final class JoinProtectionManager {
    private final int alertThreshold;
    private final long windowMillis;
    private final long alertCooldownMillis;
    private final ArrayDeque<Long> recentJoins = new ArrayDeque<>();
    private long lastAlertMillis;

    public JoinProtectionManager() {
        this(8, 20, 120);
    }

    public JoinProtectionManager(int alertThreshold, int windowSeconds, int alertCooldownSeconds) {
        if (alertThreshold < 3 || alertThreshold > 100 || windowSeconds < 5 || windowSeconds > 120
                || alertCooldownSeconds < 10 || alertCooldownSeconds > 3600) {
            throw new IllegalArgumentException("Join protection configuration is outside safe bounds.");
        }
        this.alertThreshold = alertThreshold;
        this.windowMillis = windowSeconds * 1000L;
        this.alertCooldownMillis = alertCooldownSeconds * 1000L;
    }

    public synchronized int recordJoin(UUID playerId, long nowMillis) {
        if (playerId == null) {
            return 0;
        }
        while (!recentJoins.isEmpty() && nowMillis - recentJoins.peekFirst() >= windowMillis) {
            recentJoins.removeFirst();
        }
        recentJoins.addLast(nowMillis);
        while (recentJoins.size() > 1024) {
            recentJoins.removeFirst();
        }

        if (recentJoins.size() >= alertThreshold
                && (lastAlertMillis == 0 || nowMillis - lastAlertMillis >= alertCooldownMillis)) {
            lastAlertMillis = nowMillis;
            return recentJoins.size();
        }
        return 0;
    }
}
