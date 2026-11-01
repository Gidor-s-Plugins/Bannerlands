package dev.bannerlands.listener;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.model.ChunkKey;
import dev.bannerlands.model.ClaimedChunk;
import dev.bannerlands.model.Town;
import dev.bannerlands.util.Msg;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Action-bar "entering / leaving" messages when a player crosses into another town's land. */
public final class TerritoryListener implements Listener {
    private final BannerlandsPlugin plugin;

    public TerritoryListener(BannerlandsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        int fx = from.getBlockX() >> 4, fz = from.getBlockZ() >> 4;
        int tx = to.getBlockX() >> 4, tz = to.getBlockZ() >> 4;
        if (fx == tx && fz == tz && from.getWorld().equals(to.getWorld())) return; // same chunk: nothing to do

        ClaimedChunk a = plugin.data().claims.get(new ChunkKey(from.getWorld().getUID(), fx, fz));
        ClaimedChunk b = plugin.data().claims.get(new ChunkKey(to.getWorld().getUID(), tx, tz));
        long ta = a == null ? 0 : a.getTownId(), tb = b == null ? 0 : b.getTownId();
        if (ta == tb) return; // still inside the same town (or still wilderness)

        Player p = e.getPlayer();
        if (tb != 0) {
            Town t = plugin.data().town(tb);
            if (t != null) p.sendActionBar(Msg.raw("territory.enter", Msg.p("t", t.getName())));
        } else {
            Town t = plugin.data().town(ta);
            if (t != null) p.sendActionBar(Msg.raw("territory.leave", Msg.p("t", t.getName())));
        }
    }
}