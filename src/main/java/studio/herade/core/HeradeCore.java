package studio.herade.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import studio.herade.core.commands.ModerationCommands;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.minecraft.network.chat.Component;
import studio.herade.core.mixin.ServerLoginPacketListenerAccessor;
import studio.herade.core.security.SecurityApiClient.VerificationResult;
import studio.herade.core.security.SecurityApiClient.VerificationStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
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
    private static final AuditMaintenanceManager AUDIT_MAINTENANCE_MANAGER = new AuditMaintenanceManager();
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

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (entity instanceof ServerPlayer target && VANISH_MANAGER.isVanished(target.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (entity instanceof ServerPlayer target && VANISH_MANAGER.isVanished(target.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                player instanceof ServerPlayer target && VANISH_MANAGER.isVanished(target.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
                player instanceof ServerPlayer target && VANISH_MANAGER.isVanished(target.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) ->
                player instanceof ServerPlayer target && VANISH_MANAGER.isVanished(target.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer target && VANISH_MANAGER.isVanished(target.getUUID())));

        ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
            if (!config.authenticationEnabled()) {
                return;
            }

            CompletableFuture<Void> verification = CompletableFuture.runAsync(() -> {
                if (!server.usesAuthentication()) {
                    rejectLogin(listener,
                            "Herade Core требует online-mode=true для безопасной проверки UUID.");
                }

                var profile = ((ServerLoginPacketListenerAccessor) listener).herade$getAuthenticatedProfile();
                if (profile == null || profile.id() == null || profile.name() == null) {
                    rejectLogin(listener,
                            "Не удалось подтвердить профиль Minecraft. Попробуй войти позже.");
                }

                if (securityApiClient == null) {
                    if (config.failClosed()) {
                        rejectLogin(listener, "Сервис авторизации недоступен. Попробуй войти позже.");
                    }
                    return;
                }

                VerificationResult result = securityApiClient.verify(profile.id(), profile.name()).join();
                if (auditLog != null) {
                    auditLog.append(null, "LOGIN_VERIFICATION_" + result.status().name(), profile.id(), profile.name());
                }

                if (result.status() == VerificationStatus.CHALLENGE && result.challengeCode() != null) {
                    rejectLogin(listener, "Подтверди привязку в Discord: одноразовый код "
                            + result.challengeCode() + ". Выполни /minecraft confirm с этим кодом.");
                } else if (result.status() == VerificationStatus.DENIED) {
                    rejectLogin(listener,
                            "Доступ не подтверждён. Создай или проверь заявку через Discord-бота HeradeLegend.");
                } else if (result.status() == VerificationStatus.UNAVAILABLE && config.failClosed()) {
                    rejectLogin(listener, "Сервис авторизации временно недоступен. Попробуй войти позже.");
                }
            });

            synchronizer.waitFor(verification);
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            if (VANISH_MANAGER.isVanished(sender.getUUID())) {
                return false;
            }
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

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                VANISH_MANAGER.refresh(newPlayer.getServer(), newPlayer));

        ServerLifecycleEvents.SERVER_STOPPING.register(VANISH_MANAGER::clear);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BAN_EXPIRY_MANAGER.tick(server);
            timeoutManager.tick();
            AUDIT_MAINTENANCE_MANAGER.tick();
        });

        LOGGER.info("Herade Core {} initialized.", BuildInfo.VERSION);
        LOGGER.info("Configuration loaded: authentication={}, audit={}",
                config.authenticationEnabled(), config.auditEnabled());
    }

    private static void rejectLogin(net.minecraft.server.network.ServerLoginPacketListenerImpl listener,
                                    String message) {
        listener.disconnect(Component.literal(message));
        // Fabric's LoginSynchronizer only stops the login state machine for an exceptional future.
        throw new CancellationException("Herade Core rejected this login.");
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
