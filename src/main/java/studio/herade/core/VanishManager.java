package studio.herade.core;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishManager {
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, Boolean> previousInvisible =
            new ConcurrentHashMap<>();

    public boolean isVanished(UUID playerId) {
        return playerId != null && vanishedPlayers.contains(playerId);
    }

    public boolean toggle(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            throw new IllegalArgumentException("Server and player are required.");
        }

        UUID playerId = player.getUUID();
        if (vanishedPlayers.remove(playerId)) {
            show(server, player);
            return false;
        }

        vanishedPlayers.add(playerId);
        hide(server, player);
        return true;
    }

    public void playerDisconnected(UUID playerId) {
        if (playerId != null) {
            vanishedPlayers.remove(playerId);
            previousInvisible.remove(playerId);
        }
    }

    public void refresh(MinecraftServer server, ServerPlayer player) {
        if (server != null && player != null && isVanished(player.getUUID())) {
            hide(server, player);
        }
    }

    public void hideFromNewPlayer(ServerPlayer joiningPlayer) {
        if (joiningPlayer == null || vanishedPlayers.isEmpty()) {
            return;
        }
        for (UUID vanishedId : vanishedPlayers) {
            joiningPlayer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(vanishedId)));
        }
    }

    public void clear(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (vanishedPlayers.remove(player.getUUID())) {
                show(server, player);
            }
        }
        vanishedPlayers.clear();
    }

    private void hide(MinecraftServer server, ServerPlayer player) {
        previousInvisible.putIfAbsent(player.getUUID(), player.isInvisible());
        player.setInvisible(true);
        ClientboundPlayerInfoRemovePacket packet =
                new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (!viewer.getUUID().equals(player.getUUID())) {
                viewer.connection.send(packet);
            }
        }
    }

    private void show(MinecraftServer server, ServerPlayer player) {
        Boolean wasInvisible = previousInvisible.remove(player.getUUID());
        player.setInvisible(Boolean.TRUE.equals(wasInvisible));
        ClientboundPlayerInfoUpdatePacket packet =
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(packet);
        }
    }
}
