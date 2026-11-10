package studio.herade.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import studio.herade.core.BuildInfo;
import studio.herade.core.HeradeCore;
import studio.herade.core.moderation.Punishment;
import studio.herade.core.moderation.PunishmentType;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.List;
import java.util.Locale;

public final class ModerationCommands {
    private ModerationCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("herade")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR));

        root.then(Commands.literal("status")
                .executes(context -> status(context.getSource())));
        root.then(Commands.literal("vanish")
                .executes(context -> toggleVanish(context.getSource())));
        root.then(Commands.literal("kick")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ModerationCommands::kick))));
        root.then(Commands.literal("ban")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> ban(context, 0)))));
        root.then(Commands.literal("tempban")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 525600))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> ban(context,
                                                IntegerArgumentType.getInteger(context, "minutes")))))));
        root.then(Commands.literal("timeout")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 525600))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ModerationCommands::timeout)))));
        root.then(Commands.literal("untimeout")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ModerationCommands::untimeout)));
        root.then(Commands.literal("unban")
                .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(ModerationCommands::unban)
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(ModerationCommands::unban))));
        root.then(Commands.literal("audit")
                .executes(context -> audit(context.getSource(), 10))
                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                        .executes(context -> audit(context.getSource(),
                                IntegerArgumentType.getInteger(context, "limit")))));
        root.then(Commands.literal("inspect")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> inspect(context.getSource(),
                                EntityArgument.getPlayer(context, "target")))));
        root.then(Commands.literal("punishments")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> punishments(context.getSource(),
                                EntityArgument.getPlayer(context, "target")))));

        dispatcher.register(root);
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Herade Core " + BuildInfo.VERSION
                        + " | auth=" + HeradeCore.config().authenticationEnabled()
                        + " | audit=" + HeradeCore.config().auditEnabled()
                        + " | online=" + HeradeCore.sessions().onlineCount()), false);
        return 1;
    }

    private static int toggleVanish(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean vanished = HeradeCore.vanish().toggle(source.getServer(), player);
        if (HeradeCore.auditLog() != null) {
            HeradeCore.auditLog().append(player.getUUID(),
                    vanished ? "VANISH_ENABLED" : "VANISH_DISABLED", player.getUUID(), "moderator command");
        }
        source.sendSuccess(() -> Component.literal(
                vanished ? "Vanish включён." : "Vanish выключен."), false);
        return 1;
    }

    private static int kick(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        String reason = StringArgumentType.getString(context, "reason");
        if (!validateReason(source, reason)) return 0;
        long now = System.currentTimeMillis();
        UUID actorId = source.getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;

        Punishment punishment = new Punishment(
                UUID.randomUUID(), target.getUUID(), actorId, PunishmentType.KICK, reason, now, 0);
        if (!HeradeCore.punishmentStore().issue(punishment)) {
            source.sendFailure(Component.literal("Не удалось сохранить наказание. Игрок не был отключён."));
            return 0;
        }
        target.connection.disconnect(Component.literal("Кик с сервера. Причина: " + reason));
        recordModerationAction(actorId, target.getUUID(), "KICK", reason);
        source.sendSuccess(() -> Component.literal(
                "Игрок " + target.getName().getString() + " отключён. Причина: " + reason), true);
        return 1;
    }

    private static int ban(CommandContext<CommandSourceStack> context, int minutes) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        String reason = StringArgumentType.getString(context, "reason");
        if (!validateReason(source, reason)) return 0;
        long now = System.currentTimeMillis();
        long expiresAt = minutes > 0 ? Math.addExact(now, Math.multiplyExact(minutes, 60_000L)) : 0L;
        Date expiry = expiresAt == 0 ? null : new Date(expiresAt);
        UUID actorId = source.getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        NameAndId identity = new NameAndId(target.getUUID(), target.getName().getString());
        UserBanListEntry entry = new UserBanListEntry(identity, new Date(now), "Herade Core", expiry, reason);

        if (!source.getServer().getPlayerList().getBans().add(entry)) {
            source.sendFailure(Component.literal("Не удалось сохранить блокировку в списке Minecraft."));
            return 0;
        }

        PunishmentType type = minutes > 0 ? PunishmentType.TEMP_BAN : PunishmentType.BAN;
        Punishment punishment = new Punishment(
                UUID.randomUUID(), target.getUUID(), actorId, type, reason, now, expiresAt);
        if (!HeradeCore.punishmentStore().issue(punishment)) {
            source.getServer().getPlayerList().getBans().remove(identity);
            source.sendFailure(Component.literal("Не удалось сохранить наказание. Блокировка отменена."));
            return 0;
        }
        recordModerationAction(actorId, target.getUUID(), type.name(), reason);
        target.connection.disconnect(Component.literal(
                (minutes > 0 ? "Временная блокировка" : "Постоянная блокировка")
                        + ". Причина: " + reason));
        source.sendSuccess(() -> Component.literal(
                "Игрок " + target.getName().getString()
                        + (minutes > 0 ? " заблокирован на " + minutes + " мин." : " заблокирован.")
                        + " Причина: " + reason), true);
        return 1;
    }

    private static int timeout(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        int minutes = IntegerArgumentType.getInteger(context, "minutes");
        String reason = StringArgumentType.getString(context, "reason");
        if (!validateReason(source, reason)) return 0;
        long now = System.currentTimeMillis();
        long expiresAt = Math.addExact(now, Math.multiplyExact(minutes, 60_000L));
        UUID actorId = source.getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;

        Punishment punishment = new Punishment(UUID.randomUUID(), target.getUUID(), actorId,
                PunishmentType.TIMEOUT, reason, now, expiresAt);
        if (!HeradeCore.punishmentStore().issue(punishment)) {
            source.sendFailure(Component.literal("Не удалось сохранить timeout."));
            return 0;
        }
        HeradeCore.timeouts().apply(target.getUUID(), expiresAt);
        recordModerationAction(actorId, target.getUUID(), "TIMEOUT", reason);
        target.sendSystemMessage(Component.literal("Тебе временно запрещено писать в чат. Причина: " + reason));
        source.sendSuccess(() -> Component.literal(
                "Игрок " + target.getName().getString() + " получил timeout на " + minutes + " мин."), true);
        return 1;
    }

    private static int untimeout(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        UUID actorId = source.getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        int revoked = HeradeCore.punishmentStore().revokeActiveTimeouts(
                target.getUUID(), actorId, "untimeout");
        boolean cleared = HeradeCore.timeouts().clear(target.getUUID());
        if (revoked == 0 && !cleared) {
            source.sendFailure(Component.literal("Активный timeout для игрока не найден."));
            return 0;
        }
        recordModerationAction(actorId, target.getUUID(), "UNTIMEOUT", "untimeout");
        target.sendSystemMessage(Component.literal("Твой timeout снят."));
        source.sendSuccess(() -> Component.literal(
                "Timeout снят для " + target.getName().getString() + "."), true);
        return 1;
    }

    private static int unban(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String rawUuid = StringArgumentType.getString(context, "uuid");
        final UUID playerId;
        try {
            playerId = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Некорректный UUID. Используй UUID игрока из журнала или заявки."));
            return 0;
        }

        UserBanListEntry existing = source.getServer().getPlayerList().getBans().getEntries().stream()
                .filter(entry -> entry.getUser() != null && playerId.equals(entry.getUser().id()))
                .findFirst().orElse(null);
        if (existing == null || !source.getServer().getPlayerList().getBans().remove(existing.getUser())) {
            source.sendFailure(Component.literal("Активная блокировка для этого UUID не найдена."));
            return 0;
        }

        String username = existing.getUser().name();
        UUID actorId = source.getEntity() instanceof ServerPlayer actor ? actor.getUUID() : null;
        long now = System.currentTimeMillis();
        HeradeCore.punishmentStore().revokeActiveRestrictions(playerId, actorId, "unban");
        HeradeCore.punishmentStore().issue(new Punishment(
                UUID.randomUUID(), playerId, actorId, PunishmentType.PARDON,
                "unban", now, 0));
        recordModerationAction(actorId, playerId, "UNBAN", "unban");
        source.sendSuccess(() -> Component.literal("Блокировка снята: " + username + " (" + playerId + ")."), true);
        return 1;
    }

    private static boolean validateReason(CommandSourceStack source, String reason) {
        if (reason == null || reason.isBlank()) {
            source.sendFailure(Component.literal("Укажи причину наказания."));
            return false;
        }
        if (reason.trim().length() > 500) {
            source.sendFailure(Component.literal("Причина не должна превышать 500 символов."));
            return false;
        }
        return true;
    }

    private static void recordModerationAction(UUID actorId, UUID targetId, String action, String reason) {
        if (HeradeCore.auditLog() != null) {
            HeradeCore.auditLog().append(actorId, action, targetId, reason);
        }
    }

    private static int audit(CommandSourceStack source, int limit) {
        if (HeradeCore.auditLog() == null) {
            source.sendFailure(Component.literal("Аудит отключён в конфигурации Herade Core."));
            return 0;
        }

        var events = HeradeCore.auditLog().recent(limit);
        if (events.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Записей аудита пока нет."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Последние события аудита: " + events.size()), false);
        events.forEach(event -> {
            String actor = event.actor().isBlank() ? "server" : event.actor();
            String target = event.target().isBlank() ? "-" : event.target();
            String line = Instant.ofEpochMilli(event.timestampMillis())
                    + " | " + event.action()
                    + " | actor=" + actor
                    + " | target=" + target
                    + (event.reason().isBlank() ? "" : " | " + event.reason());
            source.sendSuccess(() -> Component.literal(line), false);
        });
        return events.size();
    }

    private static int inspect(CommandSourceStack source, ServerPlayer target) {
        String coordinates = String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                target.getX(), target.getY(), target.getZ());
        String health = String.format(Locale.ROOT, "%.1f/%.1f",
                target.getHealth(), target.getMaxHealth());
        String gameMode = target.gameMode.getGameModeForPlayer().getName();
        String effects = target.getActiveEffects().stream()
                .limit(8)
                .map(effect -> effect.getEffect().value().getDisplayName().getString()
                        + " " + (effect.getAmplifier() + 1)
                        + " (" + (effect.isInfiniteDuration() ? "∞" : (effect.getDuration() / 20) + "s") + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        if (effects.isBlank()) effects = "нет";
        boolean vanished = HeradeCore.vanish().isVanished(target.getUUID());
        String sessionStarted = HeradeCore.sessions().find(target.getUUID())
                .map(session -> Instant.ofEpochMilli(session.joinedAtMillis()).toString())
                .orElse("нет данных");
        String finalEffects = effects;
        source.sendSuccess(() -> Component.literal(
                "Игрок: " + target.getName().getString()
                        + " | UUID: " + target.getUUID()
                        + " | Мир: " + target.level().dimension()
                        + " | Координаты: " + coordinates
                        + " | Режим: " + gameMode
                        + " | Здоровье: " + health
                        + " | Эффекты: " + finalEffects
                        + " | Vanish: " + vanished
                        + " | Вход: " + sessionStarted), false);
        if (HeradeCore.auditLog() != null) {
            List<studio.herade.core.AuditLog.AuditEvent> history = HeradeCore.auditLog().recent(100).stream()
                    .filter(event -> target.getUUID().toString().equals(event.target()))
                    .limit(5)
                    .toList();
            source.sendSuccess(() -> Component.literal("Последние события игрока: " + history.size()), false);
            history.forEach(event -> source.sendSuccess(() -> Component.literal(
                    Instant.ofEpochMilli(event.timestampMillis()) + " | " + event.action()
                            + (event.reason().isBlank() ? "" : " | " + event.reason())), false));
        }
        return 1;
    }

    private static int punishments(CommandSourceStack source, ServerPlayer target) {
        List<Punishment> active = HeradeCore.punishmentStore().activeFor(target.getUUID());
        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Активных наказаний для " + target.getName().getString() + " нет."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(
                "Активные наказания: " + target.getName().getString()
                        + " (" + active.size() + ")"), false);
        active.stream().limit(10).forEach(punishment -> source.sendSuccess(
                () -> Component.literal(formatPunishment(punishment)), false));
        if (active.size() > 10) {
            source.sendSuccess(() -> Component.literal(
                    "Показаны первые 10 записей из " + active.size() + "."), false);
        }
        return active.size();
    }

    private static String formatPunishment(Punishment punishment) {
        String expiry = punishment.expiresAtMillis() == 0
                ? "без срока"
                : Instant.ofEpochMilli(punishment.expiresAtMillis()).toString();
        return punishment.type().name()
                + " | " + punishment.reason()
                + " | " + expiry
                + " | case " + punishment.id();
    }
}
