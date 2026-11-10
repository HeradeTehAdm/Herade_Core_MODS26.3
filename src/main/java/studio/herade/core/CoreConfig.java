package studio.herade.core;

public record CoreConfig(
        boolean authenticationEnabled,
        String apiBaseUrl,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        boolean failClosed,
        boolean auditEnabled,
        int auditRetentionDays,
        boolean logPlayerIps
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
                false
        );
    }
}
