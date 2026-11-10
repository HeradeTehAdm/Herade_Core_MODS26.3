package studio.herade.core;

public record CoreConfig(
        boolean authenticationEnabled,
        String apiBaseUrl,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        boolean failClosed,
        boolean auditEnabled,
        int auditRetentionDays,
        boolean logPlayerIps,
        int chatMaxMessages,
        int chatWindowSeconds,
        int chatNoticeCooldownSeconds,
        int joinFloodThreshold,
        int joinFloodWindowSeconds,
        int joinAlertCooldownSeconds
) {
    public static CoreConfig defaults() {
        return new CoreConfig(
                false,
                "",
                5,
                8,
                true,
                true,
                30,
                false,
                5,
                5,
                10,
                8,
                20,
                120
        );
    }
}
