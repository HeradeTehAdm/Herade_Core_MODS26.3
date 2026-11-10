package studio.herade.core;

import java.util.ArrayDeque;
import java.util.UUID;

public final class JoinProtectionManager {
    private static final int ALERT_THRESHOLD = 8;
    private static final long WINDOW_MILLIS = 20_000;
    private static final long ALERT_COOLDOWN_MILLIS = 120_000;

    private final ArrayDeque<Long> recentJoins = new ArrayDeque<>();
    private long lastAlertMillis;

    public synchronized int recordJoin(UUID playerId, long nowMillis) {
        if (playerId == null) {
            return 0;
        }
        while (!recentJoins.isEmpty() && nowMillis - recentJoins.peekFirst() >= WINDOW_MILLIS) {
            recentJoins.removeFirst();
        }
        recentJoins.addLast(nowMillis);

        if (recentJoins.size() >= ALERT_THRESHOLD
                && nowMillis - lastAlertMillis >= ALERT_COOLDOWN_MILLIS) {
            lastAlertMillis = nowMillis;
            return recentJoins.size();
        }
        return 0;
    }
}
