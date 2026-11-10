package studio.herade.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class AuditLog {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final Path file;
    private final int retentionDays;

    public AuditLog(Path file, int retentionDays) {
        this.file = file;
        this.retentionDays = Math.max(1, Math.min(365, retentionDays));
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
            pruneExpired();
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Could not initialize audit log at {}.", file, exception);
        }
    }

    public synchronized void append(UUID actor, String action, UUID target, String reason) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Audit action must not be blank.");
        }

        long timestamp = System.currentTimeMillis();
        String line = timestamp + "\t"
                + encode(actor == null ? "" : actor.toString()) + "\t"
                + encode(action.trim()) + "\t"
                + encode(target == null ? "" : target.toString()) + "\t"
                + encode(reason == null ? "" : reason) + System.lineSeparator();

        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Failed to append an audit event.", exception);
        }
    }

    public synchronized void pruneExpired() {
        if (Files.notExists(file)) {
            return;
        }

        long cutoff = System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            List<String> retained = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('\t');
                if (separator < 1) {
                    retained.add(line);
                    continue;
                }

                try {
                    long timestamp = Long.parseLong(line.substring(0, separator));
                    if (timestamp >= cutoff) {
                        retained.add(line);
                    }
                } catch (NumberFormatException exception) {
                    // Preserve malformed rows instead of silently destroying audit evidence.
                    retained.add(line);
                }
            }

            Files.write(temporary, retained, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Could not prune expired audit events.", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
        }
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeField(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
