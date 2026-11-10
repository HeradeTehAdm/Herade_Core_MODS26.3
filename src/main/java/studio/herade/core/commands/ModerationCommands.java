package studio.herade.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import studio.herade.core.BuildInfo;
import studio.herade.core.HeradeCore;
import studio.herade.core.moderation.Punishment;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class ModerationCommands {
    private ModerationCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("herade")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("vanish")
                        .executes(context -> toggleVanish(context.getSource())))
                .then(Commands.literal("audit")
                        .executes(context -> audit(context.getSource(), 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                                .executes(context -> audit(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit"))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> inspect(context.getSource(),
                                        EntityArgument.getPlayer(context, "target")))))
                .then(Commands.literal("punishments")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> punishments(context.getSource(),
                                        EntityArgument.getPlayer(context, "target"))))));
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
        String sessionStarted = HeradeCore.sessions().find(target.getUUID())
                .map(session -> Instant.ofEpochMilli(session.joinedAtMillis()).toString())
                .orElse("нет данных");
        source.sendSuccess(() -> Component.literal(
                "Игрок: " + target.getName().getString()
                        + " | UUID: " + target.getUUID()
                        + " | Мир: " + target.level().dimension()
                        + " | Координаты: " + coordinates
                        + " | Вход: " + sessionStarted), false);
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
