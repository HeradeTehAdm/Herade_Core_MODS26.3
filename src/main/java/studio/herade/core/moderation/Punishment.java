package studio.herade.core.moderation;

import java.util.UUID;

public record Punishment(
        UUID id,
        UUID targetId,
        UUID actorId,
        PunishmentType type,
        String reason,
        long createdAtMillis,
        long expiresAtMillis
) {
    public Punishment {
        if (id == null || targetId == null || type == null) {
            throw new IllegalArgumentException("Punishment id, target and type are required.");
        }
        reason = reason == null || reason.isBlank() ? "No reason provided" : reason.trim();
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Punishment reason must be 500 characters or fewer.");
        }
        if (createdAtMillis <= 0) {
            throw new IllegalArgumentException("Creation timestamp must be positive.");
        }
        if (expiresAtMillis < 0 || (expiresAtMillis > 0 && expiresAtMillis <= createdAtMillis)) {
            throw new IllegalArgumentException("Expiry must be zero or later than creation.");
        }
        if ((type == PunishmentType.TEMP_BAN || type == PunishmentType.TIMEOUT) && expiresAtMillis == 0) {
            throw new IllegalArgumentException("Temporary punishments require an expiry timestamp.");
        }
        if ((type == PunishmentType.BAN || type == PunishmentType.KICK || type == PunishmentType.PARDON)
                && expiresAtMillis != 0) {
            throw new IllegalArgumentException("Permanent bans and kicks must not have an expiry.");
        }
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }

    public boolean isPersistentRestriction(long nowMillis) {
        return (type == PunishmentType.BAN || type == PunishmentType.TEMP_BAN || type == PunishmentType.TIMEOUT)
                && !isExpired(nowMillis);
    }
}
