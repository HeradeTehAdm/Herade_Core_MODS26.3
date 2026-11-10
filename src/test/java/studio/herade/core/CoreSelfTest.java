package studio.herade.core;

import studio.herade.core.moderation.Punishment;
import studio.herade.core.moderation.PunishmentStore;
import studio.herade.core.moderation.PunishmentType;
import studio.herade.core.security.SecurityApiSelfTest;
import studio.herade.core.TimeoutManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class CoreSelfTest {
    private CoreSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        SecurityApiSelfTest.run();
        testPunishmentModel();
        testPunishmentPersistence();
        testTimeoutPersistence();
        testChatRateLimiter();
        testJoinProtection();
        testPlayerSessions();
        testAuditPersistence();
        System.out.println("[Herade Core] All self-tests passed.");
    }

    private static void testPunishmentModel() {
        UUID id = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long created = System.currentTimeMillis();

        Punishment temporaryBan = new Punishment(id, target, null, PunishmentType.TEMP_BAN,
                "test", created, created + 60_000);
        check(temporaryBan.isPersistentRestriction(created), "temporary ban should be active");
        check(temporaryBan.isExpired(created + 60_000), "temporary ban should expire at its deadline");

        Punishment permanentBan = new Punishment(UUID.randomUUID(), target, null, PunishmentType.BAN,
                "test", created, 0);
        check(permanentBan.isPersistentRestriction(created + 10_000_000), "permanent ban should remain active");

        Punishment kick = new Punishment(UUID.randomUUID(), target, null, PunishmentType.KICK,
                "test", created, 0);
        check(!kick.isPersistentRestriction(created), "kick must not be a persistent restriction");
        Punishment pardon = new Punishment(UUID.randomUUID(), target, null, PunishmentType.PARDON,
                "test pardon", created, 0);
        check(!pardon.isPersistentRestriction(created), "pardon must not be an active restriction");

        expectIllegalArgument(() -> new Punishment(UUID.randomUUID(), target, null, PunishmentType.TIMEOUT,
                "invalid", created, 0), "temporary timeout must require an expiry");
        expectIllegalArgument(() -> new Punishment(UUID.randomUUID(), target, null, PunishmentType.BAN,
                "x".repeat(501), created, 0), "punishment reason must be bounded");
        System.out.println("[Herade Core] Punishment model: OK");
    }

    private static void testPunishmentPersistence() throws Exception {
        Path directory = Files.createTempDirectory("herade-punishments-test");
        try {
            PunishmentStore store = new PunishmentStore(directory.resolve("punishments.journal"));
            UUID target = UUID.randomUUID();
            UUID id = UUID.randomUUID();
            long created = System.currentTimeMillis();
            Punishment punishment = new Punishment(id, target, null, PunishmentType.BAN,
                    "persistent test", created, 0);

            check(store.issue(punishment), "new punishment should be appended");
            check(!store.issue(punishment), "duplicate punishment id should be rejected");
            check(store.activeFor(target).size() == 1, "punishment should be readable from journal");

            PunishmentStore reopened = new PunishmentStore(directory.resolve("punishments.journal"));
            check(reopened.activeFor(target).size() == 1, "punishment should survive store reinitialization");
            check(reopened.revoke(id, null, "test revoke"), "active punishment should be revocable");
            check(reopened.activeFor(target).isEmpty(), "revoked punishment should no longer be active");
            check(!reopened.revoke(id, null, "duplicate revoke"), "revocation should not be applied twice");
        } finally {
            deleteTree(directory);
        }
        System.out.println("[Herade Core] Punishment persistence: OK");
    }

    private static void testTimeoutPersistence() throws Exception {
        Path directory = Files.createTempDirectory("herade-timeout-test");
        try {
            PunishmentStore store = new PunishmentStore(directory.resolve("punishments.journal"));
            UUID target = UUID.randomUUID();
            long now = System.currentTimeMillis();
            Punishment timeout = new Punishment(UUID.randomUUID(), target, null, PunishmentType.TIMEOUT,
                    "test timeout", now, now + 60_000);
            check(store.issue(timeout), "timeout should be persisted");

            TimeoutManager manager = new TimeoutManager(store);
            check(manager.isTimedOut(target), "restored timeout should be active");
            check(manager.shouldNotify(target, now), "first timeout notice should be allowed");
            check(!manager.shouldNotify(target, now + 1_000), "timeout notice should be throttled");
            check(!manager.shouldNotify(target, now + 1_999),
                    "repeated blocked messages must not extend the notice cooldown");
            check(manager.shouldNotify(target, now + 2_000),
                    "timeout notice should be allowed again after two seconds");
            check(store.revokeActiveTimeouts(target, null, "test untimeout") == 1,
                    "active timeout should be revocable");
            TimeoutManager reopened = new TimeoutManager(store);
            check(!reopened.isTimedOut(target), "revoked timeout should not be restored");
            check(!reopened.shouldNotify(null, System.currentTimeMillis()),
                    "timeout notice must reject a null player id");
        } finally {
            deleteTree(directory);
        }
        System.out.println("[Herade Core] Timeout persistence: OK");
    }

    private static void testChatRateLimiter() {
        ChatRateLimiter limiter = new ChatRateLimiter();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            check(limiter.check(player, 1_000 + i).allowed(), "first five chat messages should pass");
        }
        ChatRateLimiter.Decision blocked = limiter.check(player, 1_010);
        check(!blocked.allowed() && blocked.notifyPlayer(), "sixth message should be blocked and notify once");
        check(!limiter.check(player, 1_100).notifyPlayer(), "chat notice should be throttled");
        check(limiter.check(player, 6_100).allowed(), "chat window should reset after five seconds");
        limiter.clear(player);
        System.out.println("[Herade Core] Chat rate limiter: OK");
    }

    private static void testJoinProtection() {
        JoinProtectionManager protection = new JoinProtectionManager();
        int alert = 0;
        for (int i = 0; i < 8; i++) {
            alert = protection.recordJoin(UUID.randomUUID(), 10_000 + i * 100L);
        }
        check(alert == 8, "eighth rapid join should trigger one alert");
        check(protection.recordJoin(UUID.randomUUID(), 10_900) == 0,
                "join flood alert should respect its cooldown");
        System.out.println("[Herade Core] Join protection: OK");
    }

    private static void testPlayerSessions() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        UUID player = UUID.randomUUID();
        tracker.joined(player, "SessionTester");
        check(tracker.onlineCount() == 1, "joined player should count as online");
        PlayerSessionTracker.Session active = tracker.find(player).orElseThrow();
        check(active.online() && "SessionTester".equals(active.playerName()),
                "session should retain the player's online state and name");
        tracker.left(player, "SessionTester");
        PlayerSessionTracker.Session offline = tracker.find(player).orElseThrow();
        check(!offline.online() && offline.lastSeenAtMillis() >= active.joinedAtMillis(),
                "disconnect should retain last-seen time");
        check(tracker.onlineCount() == 0, "disconnected player should not count as online");
        System.out.println("[Herade Core] Player sessions: OK");
    }

    private static void testAuditPersistence() throws Exception {
        Path directory = Files.createTempDirectory("herade-audit-test");
        try {
            Path file = directory.resolve("audit.log");
            AuditLog audit = new AuditLog(file, 30);
            UUID target = UUID.randomUUID();
            audit.append(null, "TEST_EVENT", target, "reason with tabs\tand newline\n");
            String contents = Files.readString(file).trim();
            String[] fields = contents.split("\\t", -1);
            check(fields.length == 5, "audit event should contain five encoded fields");
            check("TEST_EVENT".equals(AuditLog.decodeField(fields[2])), "audit action should be persisted");
            check(contents.lines().count() == 1, "encoded audit fields must remain on one line");
            audit.pruneExpired();
            check(Files.exists(file), "audit file should remain after pruning");
        } finally {
            deleteTree(directory);
        }
        System.out.println("[Herade Core] Audit persistence: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
