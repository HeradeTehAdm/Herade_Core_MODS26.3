package studio.herade.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import studio.herade.core.HeradeCore;
import studio.herade.core.security.SecurityApiClient;
import studio.herade.core.security.SecurityApiClient.VerificationResult;
import studio.herade.core.security.SecurityApiClient.VerificationStatus;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MinecraftLinkCommand {
    private static final long COOLDOWN_MILLIS = 15_000L;
    private static final ConcurrentMap<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private MinecraftLinkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("minecraft")
                .then(Commands.literal("link")
                        .executes(context -> link(context.getSource()))));
    }

    private static int link(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        if (!HeradeCore.config().authenticationEnabled()) {
            player.sendSystemMessage(Component.literal(
                    "Регистрация Minecraft через Discord сейчас отключена."));
            return 0;
        }

        SecurityApiClient api = HeradeCore.securityApiClient();
        if (api == null) {
            player.sendSystemMessage(Component.literal(
                    "Сервис регистрации недоступен. Сообщи администрации сервера."));
            return 0;
        }

        long now = System.currentTimeMillis();
        Long previous = LAST_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < COOLDOWN_MILLIS) {
            long remaining = (COOLDOWN_MILLIS - (now - previous) + 999L) / 1000L;
            player.sendSystemMessage(Component.literal(
                    "Подожди " + remaining + " сек. перед повторным запросом кода."));
            return 0;
        }
        LAST_REQUEST.put(player.getUUID(), now);

        UUID playerId = player.getUUID();
        String playerName = player.getName().getString();
        player.sendSystemMessage(Component.literal(
                "Herade Core: проверяю заявку на привязку Minecraft..."));

        api.verify(playerId, playerName).whenComplete((result, error) ->
                source.getServer().execute(() -> {
                    ServerPlayer online = source.getServer().getPlayerList().getPlayer(playerId);
                    if (online == null) {
                        LAST_REQUEST.remove(playerId);
                        return;
                    }

                    if (error != null || result == null) {
                        online.sendSystemMessage(Component.literal(
                                "Не удалось связаться с сервисом регистрации. Попробуй позже."));
                        LAST_REQUEST.remove(playerId);
                        return;
                    }

                    sendResult(online, result);
                    if (result.status() == VerificationStatus.UNAVAILABLE) {
                        LAST_REQUEST.remove(playerId);
                    }
                }));

        return 1;
    }

    private static void sendResult(ServerPlayer player, VerificationResult result) {
        if (result.status() == VerificationStatus.CHALLENGE
                && result.challengeCode() != null
                && result.challengeCode().matches("[0-9]{8}")) {
            player.sendSystemMessage(Component.literal(
                    "Herade Core: твой одноразовый код привязки: " + result.challengeCode()));
            player.sendSystemMessage(Component.literal(
                    "Введи в Discord: /minecraft confirm и укажи этот код. Код действует 10 минут."));
            player.sendSystemMessage(Component.literal(
                    "Это личное сообщение — другие игроки его не видят."));
            return;
        }

        if (result.status() == VerificationStatus.APPROVED) {
            player.sendSystemMessage(Component.literal(
                    "Твой Minecraft-аккаунт уже привязан и одобрен. Доступ подтверждён."));
            LAST_REQUEST.remove(player.getUUID());
            return;
        }

        if (result.status() == VerificationStatus.DENIED) {
            player.sendSystemMessage(Component.literal(
                    "Активная заявка не найдена или истекла. Сначала создай её через Discord: /minecraft link."));
            LAST_REQUEST.remove(player.getUUID());
            return;
        }

        player.sendSystemMessage(Component.literal(
                "Сервис регистрации временно недоступен. Попробуй позже."));
    }

    public static void clear(UUID playerId) {
        LAST_REQUEST.remove(playerId);
    }
}
