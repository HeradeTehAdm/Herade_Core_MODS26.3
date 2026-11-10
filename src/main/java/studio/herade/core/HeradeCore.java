package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import studio.herade.core.commands.ModerationCommands;
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
    private static final PlayerSessionTracker SESSION_TRACKER = new PlayerSessionTracker();
    private static final VanishManager VANISH_MANAGER = new VanishManager();
    private static TimeoutManager timeoutManager;
    private static final BanExpiryManager BAN_EXPIRY_MANAGER = new BanExpiryManager();
    private static ChatRateLimiter chatRateLimiter;
    private static JoinProtectionManager joinProtectionManager;

    @Override
    public void onInitialize() {
        config = ConfigManager.load();
        chatRateLimiter = new ChatRateLimiter(config.chatMaxMessages(),
                config.chatWindowSeconds(), config.chatNoticeCooldownSeconds());
        joinProtectionManager = new JoinProtectionManager(config.joinFloodThreshold(),
                config.joinFloodWindowSeconds(), config.joinAlertCooldownSeconds());
        Path dataDirectory = FabricLoader.getInstance().getConfigDir().resolve("herade-core");
        punishmentStore = new PunishmentStore(dataDirectory.resolve("punishments.journal"));
        timeoutManager = new TimeoutManager(punishmentStore);

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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ModerationCommands.register(dispatcher));

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

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            if (timeoutManager != null && timeoutManager.isTimedOut(sender.getUUID())) {
                if (timeoutManager.shouldNotify(sender.getUUID())) {
                    sender.sendSystemMessage(Component.literal("На тебе действует временный запрет на отправку сообщений в чат."));
                }
                return false;
            }

            ChatRateLimiter.Decision decision = chatRateLimiter.check(sender.getUUID());
            if (!decision.allowed()) {
                if (decision.notifyPlayer()) {
                    sender.sendSystemMessage(Component.literal("Слишком много сообщений. Сделай небольшую паузу."));
                }
                if (decision.notifyPlayer() && auditLog != null) {
                    auditLog.append(null, "CHAT_RATE_LIMITED", sender.getUUID(), "rate threshold exceeded");
                }
                return false;
            }
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            VANISH_MANAGER.hideFromNewPlayer(handler.player);
            SESSION_TRACKER.joined(handler.player.getUUID(), handler.player.getName().getString());
            int joinBurst = joinProtectionManager.recordJoin(handler.player.getUUID(), System.currentTimeMillis());
            if (joinBurst > 0) {
                LOGGER.warn("Possible join flood detected: {} joins in the rolling window.", joinBurst);
                if (auditLog != null) {
                    auditLog.append(null, "POSSIBLE_JOIN_FLOOD", handler.player.getUUID(),
                            "joins_in_20s=" + joinBurst);
                }
            }
            if (auditLog != null) {
                auditLog.append(null, "PLAYER_JOIN", handler.player.getUUID(),
                        handler.player.getName().getString());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            VANISH_MANAGER.playerDisconnected(handler.player.getUUID());
            chatRateLimiter.clear(handler.player.getUUID());
            SESSION_TRACKER.left(handler.player.getUUID(), handler.player.getName().getString());
            if (auditLog != null) {
                auditLog.append(null, "PLAYER_LEAVE", handler.player.getUUID(),
                        handler.player.getName().getString());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(VANISH_MANAGER::clear);
        ServerTickEvents.END_SERVER_TICK.register(BAN_EXPIRY_MANAGER::tick);

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

    public static PlayerSessionTracker sessions() {
        return SESSION_TRACKER;
    }

    public static VanishManager vanish() {
        return VANISH_MANAGER;
    }

    public static TimeoutManager timeouts() {
        return timeoutManager;
    }
}
