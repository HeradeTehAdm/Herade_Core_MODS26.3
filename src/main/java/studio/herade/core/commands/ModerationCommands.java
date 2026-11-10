package studio.herade.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
