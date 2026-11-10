package studio.herade.core.moderation;

import studio.herade.core.HeradeCore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PunishmentStore {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final Path file;

    public PunishmentStore(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Could not initialize punishment journal at {}.", file, exception);
        }
    }

    public synchronized boolean issue(Punishment punishment) {
        if (punishment == null) {
            throw new IllegalArgumentException("Punishment is required.");
        }
        if (findAll().containsKey(punishment.id())) {
            return false;
        }
        return append("ISSUE\t" + punishment.id()
                + "\t" + punishment.targetId()
                + "\t" + (punishment.actorId() == null ? "" : punishment.actorId())
                + "\t" + punishment.type().name()
                + "\t" + punishment.createdAtMillis()
                + "\t" + punishment.expiresAtMillis()
                + "\t" + encode(punishment.reason()));
    }

    public synchronized boolean revoke(UUID punishmentId, UUID actorId, String reason) {
        if (punishmentId == null || !findAll().containsKey(punishmentId)) {
            return false;
        }
        return append("REVOKE\t" + punishmentId
                + "\t" + (actorId == null ? "" : actorId)
                + "\t" + System.currentTimeMillis()
                + "\t" + encode(reason == null ? "" : reason));
    }

    public synchronized int revokeActiveTimeouts(UUID targetId, UUID actorId, String reason) {
        if (targetId == null) {
            return 0;
        }
        int revoked = 0;
        long now = System.currentTimeMillis();
        for (Punishment punishment : findAll().values()) {
            if (punishment.targetId().equals(targetId)
                    && punishment.type() == PunishmentType.TIMEOUT
                    && punishment.isPersistentRestriction(now)) {
                boolean saved = append("REVOKE\t" + punishment.id()
                        + "\t" + (actorId == null ? "" : actorId)
                        + "\t" + now
                        + "\t" + encode(reason == null ? "" : reason));
                if (saved) revoked++;
            }
        }
        return revoked;
    }

    public synchronized int revokeActiveBans(UUID targetId, UUID actorId, String reason) {
        if (targetId == null) {
            return 0;
        }
        int revoked = 0;
        long now = System.currentTimeMillis();
        for (Punishment punishment : findAll().values()) {
            if (punishment.targetId().equals(targetId)
                    && (punishment.type() == PunishmentType.BAN || punishment.type() == PunishmentType.TEMP_BAN)
                    && punishment.isPersistentRestriction(now)) {
                boolean saved = append("REVOKE\t" + punishment.id()
                        + "\t" + (actorId == null ? "" : actorId)
                        + "\t" + now
                        + "\t" + encode(reason == null ? "" : reason));
                if (saved) {
                    revoked++;
                }
            }
        }
        return revoked;
    }

    public synchronized List<Punishment> activeFor(UUID targetId) {
        if (targetId == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return findAll().values().stream()
                .filter(p -> p.targetId().equals(targetId) && p.isPersistentRestriction(now))
                .toList();
    }

    public synchronized List<Punishment> all() {
        return List.copyOf(findAll().values());
    }

    private Map<UUID, Punishment> findAll() {
        Map<UUID, Punishment> punishments = new LinkedHashMap<>();
        if (Files.notExists(file)) {
            return punishments;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\t", -1);
                if (fields.length == 8 && "ISSUE".equals(fields[0])) {
                    try {
                        UUID id = UUID.fromString(fields[1]);
                        UUID target = UUID.fromString(fields[2]);
                        UUID actor = fields[3].isBlank() ? null : UUID.fromString(fields[3]);
                        Punishment punishment = new Punishment(id, target, actor,
                                PunishmentType.valueOf(fields[4]),
                                decode(fields[7]),
                                Long.parseLong(fields[5]),
                                Long.parseLong(fields[6]));
                        punishments.put(id, punishment);
                    } catch (RuntimeException exception) {
                        HeradeCore.LOGGER.warn("Skipping malformed punishment journal entry.");
                    }
                } else if (fields.length == 5 && "REVOKE".equals(fields[0])) {
                    try {
                        punishments.remove(UUID.fromString(fields[1]));
                    } catch (RuntimeException exception) {
                        HeradeCore.LOGGER.warn("Skipping malformed punishment revocation entry.");
                    }
                }
            }
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Could not read punishment journal.", exception);
        }
        return punishments;
    }

    private boolean append(String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Could not append punishment journal entry.", exception);
            return false;
        }
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
