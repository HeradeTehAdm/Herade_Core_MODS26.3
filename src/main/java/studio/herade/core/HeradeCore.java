package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import studio.herade.core.moderation.PunishmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class HeradeCore implements ModInitializer {
    public static final String MOD_ID = "herade_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CoreConfig config;
    private static AuditLog auditLog;
    private static PunishmentStore punishmentStore;

    @Override
    public void onInitialize() {
        config = ConfigManager.load();
        Path dataDirectory = FabricLoader.getInstance().getConfigDir().resolve("herade-core");
        punishmentStore = new PunishmentStore(dataDirectory.resolve("punishments.journal"));

        if (config.auditEnabled()) {
            Path auditPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("herade-core")
                    .resolve("audit.log");
            auditLog = new AuditLog(auditPath, config.auditRetentionDays());
        }

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (auditLog != null) {
                auditLog.append(null, "PLAYER_JOIN", handler.player.getUUID(),
                        handler.player.getName().getString());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (auditLog != null) {
                auditLog.append(null, "PLAYER_LEAVE", handler.player.getUUID(),
                        handler.player.getName().getString());
            }
        });

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

    public static PunishmentStore punishmentStore() {
        return punishmentStore;
    }
}
