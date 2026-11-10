package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeradeCore implements ModInitializer {
    public static final String MOD_ID = "herade_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Herade Core {} initialized.", BuildInfo.VERSION);
    }
}
