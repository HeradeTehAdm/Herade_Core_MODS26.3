package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.minecraft.network.chat.Component;
import studio.herade.core.mixin.ServerLoginPacketListenerAccessor;
import studio.herade.core.security.SecurityApiClient.VerificationResult;
import studio.herade.core.security.SecurityApiClient.VerificationStatus;

import java.util.concurrent.CompletableFuture;
import studio.herade.core.moderation.PunishmentStore;
import studio.herade.core.security.SecurityApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class HeradeCore implements ModInitializer {
    public static final String MOD_ID = "herade_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CoreConfig config;
    private static AuditLog auditLog;
    private static PunishmentStore punishmentStore;
    private static SecurityApiClient securityApiClient;

    @Override
    public void onInitialize() {
        config = ConfigManager.load();
        Path dataDirectory = FabricLoader.getInstance().getConfigDir().resolve("herade-core");
        punishmentStore = new PunishmentStore(dataDirectory.resolve("punishments.journal"));

        if (config.authenticationEnabled()) {
            try {
                securityApiClient = new SecurityApiClient(config);
            } catch (IllegalArgumentException exception) {
                LOGGER.error("Security API configuration is invalid; login verification cannot start.", exception);
            }
        }

        if (config.auditEnabled()) {
            Path auditPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("herade-core")
                    .resolve("audit.log");
            auditLog = new AuditLog(auditPath, config.auditRetentionDays());
        }

        ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
            if (!config.authenticationEnabled()) {
                return;
            }

            CompletableFuture<Void> verification = CompletableFuture.runAsync(() -> {
                if (!server.usesAuthentication()) {
                    listener.disconnect(Component.literal(
                            "Herade Core требует online-mode=true для безопасной проверки UUID."));
                    return;
                }

                var profile = ((ServerLoginPacketListenerAccessor) listener).herade$getAuthenticatedProfile();
                if (profile == null || profile.id() == null || profile.name() == null) {
                    listener.disconnect(Component.literal(
                            "Не удалось подтвердить профиль Minecraft. Попробуй войти позже."));
                    return;
                }

                if (securityApiClient == null) {
                    if (config.failClosed()) {
                        listener.disconnect(Component.literal(
                                "Сервис авторизации недоступен. Попробуй войти позже."));
                    }
                    return;
                }

                VerificationResult result = securityApiClient.verify(profile.id(), profile.name()).join();
                if (auditLog != null) {
                    auditLog.append(null, "LOGIN_VERIFICATION_" + result.status().name(), profile.id(), profile.name());
                }

                if (result.status() == VerificationStatus.CHALLENGE && result.challengeCode() != null) {
                    listener.disconnect(Component.literal(
                            "Подтверди привязку в Discord: одноразовый код " + result.challengeCode()
                                    + ". Выполни /minecraft confirm с этим кодом."));
                } else if (result.status() == VerificationStatus.DENIED) {
                    listener.disconnect(Component.literal(
                            "Доступ не подтверждён. Создай или проверь заявку через Discord-бота HeradeLegend."));
                } else if (result.status() == VerificationStatus.UNAVAILABLE && config.failClosed()) {
                    listener.disconnect(Component.literal(
                            "Сервис авторизации временно недоступен. Попробуй войти позже."));
                }
            });

            synchronizer.waitFor(verification);
        });

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

    public static SecurityApiClient securityApiClient() {
        return securityApiClient;
    }
}
