package studio.herade.core;

public final class AuditMaintenanceManager {
    private static final long PRUNE_INTERVAL_TICKS = 20L * 60L * 60L * 24L;
    private final java.util.concurrent.atomic.AtomicBoolean pruning = new java.util.concurrent.atomic.AtomicBoolean();
    private long ticks;

    public void tick() {
        if (++ticks < PRUNE_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        AuditLog auditLog = HeradeCore.auditLog();
        if (auditLog != null && pruning.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    auditLog.pruneExpired();
                } finally {
                    pruning.set(false);
                }
            });
        }
    }
}
