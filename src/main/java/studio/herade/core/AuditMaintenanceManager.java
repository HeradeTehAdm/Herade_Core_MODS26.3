package studio.herade.core;

public final class AuditMaintenanceManager {
    private static final long PRUNE_INTERVAL_TICKS = 20L * 60L * 60L * 24L;
    private long ticks;

    public void tick() {
        if (++ticks < PRUNE_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        AuditLog auditLog = HeradeCore.auditLog();
        if (auditLog != null) {
            auditLog.pruneExpired();
        }
    }
}
