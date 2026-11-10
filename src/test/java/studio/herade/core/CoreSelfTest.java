package studio.herade.core;

import studio.herade.core.moderation.Punishment;
import studio.herade.core.moderation.PunishmentStore;
import studio.herade.core.moderation.PunishmentType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class CoreSelfTest {
    private CoreSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        testPunishmentModel();
        testPunishmentPersistence();
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

        expectIllegalArgument(() -> new Punishment(UUID.randomUUID(), target, null, PunishmentType.TIMEOUT,
                "invalid", created, 0), "temporary timeout must require an expiry");
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
