package studio.herade.core;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ConfigManager {
    private static final String FILE_NAME = "herade-core.properties";

    private ConfigManager() {
    }

    public static CoreConfig load() {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("herade-core");
        Path file = directory.resolve(FILE_NAME);
        Properties properties = new Properties();
        CoreConfig defaults = CoreConfig.defaults();

        try {
            Files.createDirectories(directory);
            if (Files.notExists(file)) {
                writeDefaults(file, defaults);
            }

            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            }
        } catch (IOException exception) {
            HeradeCore.LOGGER.error("Could not load Herade Core configuration. Using safe defaults.", exception);
            return defaults;
        }

        boolean authenticationEnabled = readBoolean(properties, "authentication.enabled",
                defaults.authenticationEnabled());
        String apiBaseUrl = properties.getProperty("authentication.api-base-url",
                defaults.apiBaseUrl()).trim();
        int connectTimeout = readBoundedInt(properties, "authentication.connect-timeout-seconds",
                defaults.connectTimeoutSeconds(), 1, 30);
        int requestTimeout = readBoundedInt(properties, "authentication.request-timeout-seconds",
                defaults.requestTimeoutSeconds(), 1, 60);
        boolean failClosed = readBoolean(properties, "authentication.fail-closed", defaults.failClosed());
        boolean auditEnabled = readBoolean(properties, "audit.enabled", defaults.auditEnabled());
        int retentionDays = readBoundedInt(properties, "audit.retention-days",
                defaults.auditRetentionDays(), 1, 365);
        boolean logPlayerIps = readBoolean(properties, "privacy.log-player-ips", defaults.logPlayerIps());
        int chatMaxMessages = readBoundedInt(properties, "protection.chat-max-messages",
                defaults.chatMaxMessages(), 1, 30);
        int chatWindowSeconds = readBoundedInt(properties, "protection.chat-window-seconds",
                defaults.chatWindowSeconds(), 1, 60);
        int chatNoticeCooldownSeconds = readBoundedInt(properties, "protection.chat-notice-cooldown-seconds",
                defaults.chatNoticeCooldownSeconds(), 1, 60);
        int joinFloodThreshold = readBoundedInt(properties, "protection.join-flood-threshold",
                defaults.joinFloodThreshold(), 3, 100);
        int joinFloodWindowSeconds = readBoundedInt(properties, "protection.join-flood-window-seconds",
                defaults.joinFloodWindowSeconds(), 5, 120);
        int joinAlertCooldownSeconds = readBoundedInt(properties, "protection.join-alert-cooldown-seconds",
                defaults.joinAlertCooldownSeconds(), 10, 3600);

        if (authenticationEnabled && apiBaseUrl.isBlank()) {
            HeradeCore.LOGGER.warn("Authentication is enabled but authentication.api-base-url is empty. "
                    + "Authentication integration will remain inactive until configured.");
        }

        return new CoreConfig(authenticationEnabled, apiBaseUrl, connectTimeout, requestTimeout,
                failClosed, auditEnabled, retentionDays, logPlayerIps,
                chatMaxMessages, chatWindowSeconds, chatNoticeCooldownSeconds,
                joinFloodThreshold, joinFloodWindowSeconds, joinAlertCooldownSeconds);
    }

    private static void writeDefaults(Path file, CoreConfig defaults) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("authentication.enabled", Boolean.toString(defaults.authenticationEnabled()));
        properties.setProperty("authentication.api-base-url", defaults.apiBaseUrl());
        properties.setProperty("authentication.connect-timeout-seconds",
                Integer.toString(defaults.connectTimeoutSeconds()));
        properties.setProperty("authentication.request-timeout-seconds",
                Integer.toString(defaults.requestTimeoutSeconds()));
        properties.setProperty("authentication.fail-closed", Boolean.toString(defaults.failClosed()));
        properties.setProperty("audit.enabled", Boolean.toString(defaults.auditEnabled()));
        properties.setProperty("audit.retention-days", Integer.toString(defaults.auditRetentionDays()));
        properties.setProperty("privacy.log-player-ips", Boolean.toString(defaults.logPlayerIps()));
        properties.setProperty("protection.chat-max-messages", Integer.toString(defaults.chatMaxMessages()));
        properties.setProperty("protection.chat-window-seconds", Integer.toString(defaults.chatWindowSeconds()));
        properties.setProperty("protection.chat-notice-cooldown-seconds",
                Integer.toString(defaults.chatNoticeCooldownSeconds()));
        properties.setProperty("protection.join-flood-threshold", Integer.toString(defaults.joinFloodThreshold()));
        properties.setProperty("protection.join-flood-window-seconds",
                Integer.toString(defaults.joinFloodWindowSeconds()));
        properties.setProperty("protection.join-alert-cooldown-seconds",
                Integer.toString(defaults.joinAlertCooldownSeconds()));
        properties.setProperty("security.api-token-env", "HERADE_CORE_API_TOKEN");

        try (Writer writer = Files.newBufferedWriter(file)) {
            properties.store(writer, "Herade Core configuration. Keep API credentials in environment variables.");
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        HeradeCore.LOGGER.warn("Invalid boolean value for '{}'; using default.", key);
        return fallback;
    }

    private static int readBoundedInt(Properties properties, String key, int fallback, int minimum, int maximum) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException("outside allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            HeradeCore.LOGGER.warn("Invalid value for '{}'; expected {}..{}. Using default.",
                    key, minimum, maximum);
            return fallback;
        }
    }
}
