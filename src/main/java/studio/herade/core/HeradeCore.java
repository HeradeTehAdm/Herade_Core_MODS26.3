package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeradeCore implements ModInitializer {
    public static final String MOD_ID = "herade_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CoreConfig config;

    @Override
    public void onInitialize() {
        config = ConfigManager.load();
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
}
