package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class HeradeCore implements ModInitializer {
    public static final String MOD_ID = "herade_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CoreConfig config;
    private static AuditLog auditLog;

    @Override
    public void onInitialize() {
        config = ConfigManager.load();
        if (config.auditEnabled()) {
            Path auditPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("herade-core")
                    .resolve("audit.log");
            auditLog = new AuditLog(auditPath, config.auditRetentionDays());
        }

        LOGGER.info("Herade Core {} initialized.", BuildInfo.VERSION);
        LOGGER.info("Configuration loaded: authentication={}, audit={}",
                config.authenticationEnabled(), config.auditEnabled());
    }

    public static CoreConfig config() {
        if (config == null) {
            throw new IllegalStateException("Herade Core has not been initialized yet.");
        }
        return config;
    }

    public static AuditLog auditLog() {
        return auditLog;
    }
}
