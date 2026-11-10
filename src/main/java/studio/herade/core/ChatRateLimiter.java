package studio.herade.core;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatRateLimiter {
    public record Decision(boolean allowed, boolean notifyPlayer) {
    }

    private static final int MAX_MESSAGES = 5;
    private static final long WINDOW_MILLIS = 5_000;
    private static final long NOTICE_COOLDOWN_MILLIS = 10_000;

    private static final class Window {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        private long lastNoticeMillis;
    }

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public Decision check(UUID playerId) {
        return check(playerId, System.currentTimeMillis());
    }

    public Decision check(UUID playerId, long nowMillis) {
        if (playerId == null) {
            return new Decision(false, false);
        }
        Window window = windows.computeIfAbsent(playerId, ignored -> new Window());
        synchronized (window) {
            while (!window.timestamps.isEmpty()
                    && nowMillis - window.timestamps.peekFirst() >= WINDOW_MILLIS) {
                window.timestamps.removeFirst();
            }

            if (window.timestamps.size() >= MAX_MESSAGES) {
                boolean notify = window.lastNoticeMillis == 0 || nowMillis - window.lastNoticeMillis >= NOTICE_COOLDOWN_MILLIS;
                if (notify) {
                    window.lastNoticeMillis = nowMillis;
                }
                return new Decision(false, notify);
            }

            window.timestamps.addLast(nowMillis);
            return new Decision(true, false);
        }
    }

    public void clear(UUID playerId) {
        if (playerId != null) {
            windows.remove(playerId);
        }
    }
}
