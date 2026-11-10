package studio.herade.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;

import java.util.ArrayList;
import java.util.List;

public final class BanExpiryManager {
    private static final int SWEEP_INTERVAL_TICKS = 20;
    private int ticksUntilSweep;

    public void tick(MinecraftServer server) {
        if (++ticksUntilSweep < SWEEP_INTERVAL_TICKS) {
            return;
        }
        ticksUntilSweep = 0;
        sweep(server);
    }

    public void sweep(MinecraftServer server) {
        List<UserBanListEntry> entries = new ArrayList<>(
                server.getPlayerList().getBans().getEntries());
        for (UserBanListEntry entry : entries) {
            if (!entry.hasExpired()) {
                continue;
            }
            NameAndId user = entry.getUser();
            if (user != null && server.getPlayerList().getBans().remove(user)) {
                if (HeradeCore.auditLog() != null) {
                    HeradeCore.auditLog().append(null, "TEMP_BAN_EXPIRED", user.id(), user.name());
                }
            }
        }
    }
}
