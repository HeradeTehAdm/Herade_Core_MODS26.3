package studio.herade.core;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatRateLimiter {
    public record Decision(boolean allowed, boolean notifyPlayer) {
    }

    private final int maxMessages;
    private final long windowMillis;
    private final long noticeCooldownMillis;

    private static final class Window {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        private long lastNoticeMillis;
    }

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public ChatRateLimiter() {
        this(5, 5, 10);
    }

    public ChatRateLimiter(int maxMessages, int windowSeconds, int noticeCooldownSeconds) {
        if (maxMessages < 1 || maxMessages > 30 || windowSeconds < 1 || windowSeconds > 60
                || noticeCooldownSeconds < 1 || noticeCooldownSeconds > 60) {
            throw new IllegalArgumentException("Chat rate limit configuration is outside safe bounds.");
        }
        this.maxMessages = maxMessages;
        this.windowMillis = windowSeconds * 1000L;
        this.noticeCooldownMillis = noticeCooldownSeconds * 1000L;
    }

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
                    && nowMillis - window.timestamps.peekFirst() >= windowMillis) {
                window.timestamps.removeFirst();
            }

            if (window.timestamps.size() >= maxMessages) {
                boolean notify = window.lastNoticeMillis == 0 || nowMillis - window.lastNoticeMillis >= noticeCooldownMillis;
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
