package dev.bannerlands.war;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.model.*;
import dev.bannerlands.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlagWarManager {
    public enum Side { ATTACKER, DEFENDER, NONE }
    public enum Outcome { CAPTURED, DEFENDED, TIMEOUT, ABANDONED, CANCELLED }

    private final BannerlandsPlugin plugin;
    private final Map<ChunkKey, FlagWar> active = new ConcurrentHashMap<>();

    public FlagWarManager(BannerlandsPlugin plugin) { this.plugin = plugin; }

    public FlagWar at(ChunkKey key) { return active.get(key); }

    // ------------------------------------------------------------------ starting a siege

    /** Plants a banner in the chunk the player stands in and starts the capture loop. */
    public void start(Player p) {
        DataManager data = plugin.data();
        FileConfiguration cfg = plugin.getConfig();
        Block at = p.getLocation().getBlock();
        ChunkKey key = new ChunkKey(p.getWorld().getUID(), at.getX() >> 4, at.getZ() >> 4);
        ClaimedChunk chunk = data.claims.get(key);
        if (chunk == null) { Msg.send(p, "war.stand_inside_the_enemy"); return; }
        Town defender = data.town(chunk.getTownId());
        if (defender == null || defender.getNationId() == 0) {
            Msg.send(p, "war.that_town_isn_t"); return;
        }
        Town attackerTown = null;
        Set<Long> mine = data.playerTowns.get(p.getUniqueId());
        if (mine != null) {
            for (long id : mine) {
                Town t = data.town(id);
                if (t != null && t.getNationId() != 0
                        && data.relation(t.getNationId(), defender.getNationId()) == Relation.WAR) { attackerTown = t; break; }
            }
        }
        if (attackerTown == null) {
            Msg.send(p, "war.your_nation_isn_t"); return;
        }
        if (active.containsKey(key)) { Msg.send(p, "war.this_chunk_is_already"); return; }
        if (chunk.isRaided()) { Msg.send(p, "war.this_chunk_is_already_2"); return; }

        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("flagwar.cooldown-minutes", 60) * 60_000L;
        if (now - chunk.getLastSiege() < cooldown) {
            long mins = (cooldown - (now - chunk.getLastSiege())) / 60_000L + 1;
            Msg.send(p, "war.this_chunk_was_sieged", Msg.p("m", mins)); return;
        }
        final long attackerNation = attackerTown.getNationId();
        long running = active.values().stream().filter(w -> w.attackerNation() == attackerNation).count();
        if (running >= cfg.getInt("flagwar.max-sieges-per-nation", 3)) {
            Msg.send(p, "war.your_nation_already_runs"); return;
        }
        int online = 0;
        for (UUID u : defender.getMembers().keySet()) if (Bukkit.getPlayer(u) != null) online++;
        if (online < cfg.getInt("flagwar.min-defenders-online", 1)) {
            Msg.send(p, "war.not_enough_defenders_are"); return;
        }
        if (!at.isPassable() || at.isLiquid() || !at.getRelative(BlockFace.DOWN).getType().isSolid()) {
            Msg.send(p, "war.stand_on_solid_ground"); return;
        }

        Material banner = Material.matchMaterial(cfg.getString("flagwar.banner", "RED_BANNER"));
        if (banner == null) banner = Material.RED_BANNER;
        BlockData original = at.getBlockData();
        at.setType(banner, false);

        FlagWar war = new FlagWar(plugin, this, chunk, defender, attackerTown, p.getUniqueId(), at, original);
        active.put(key, war);
        war.runTaskTimer(plugin, 20L, 20L);

        Nation an = data.nation(attackerNation), dn = data.nation(defender.getNationId());
        data.broadcastNations(Msg.c("war.is_attacking_chunk_of",
                Msg.p("a", an == null ? "?" : an.getName()), Msg.p("x", key.x()), Msg.p("z", key.z()),
                Msg.p("t", defender.getName()), Msg.p("d", dn == null ? "?" : dn.getName())),
                attackerNation, defender.getNationId());
    }

    // ------------------------------------------------------------------ roles around the flag

    public Side side(Player p, FlagWar war) {
        DataManager data = plugin.data();
        UUID u = p.getUniqueId();
        if (war.defender().getMembers().containsKey(u)) return Side.DEFENDER;
        Set<Long> ids = data.playerTowns.get(u);
        if (ids == null) return Side.NONE;
        boolean att = false, def = false;
        for (long id : ids) {
            Town t = data.town(id);
            if (t == null || t.getNationId() == 0) continue;
            long n = t.getNationId();
            if (n == war.defenderNation()) def = true;
            else if (n == war.attackerNation()) att = true;
            else if (data.relation(n, war.defenderNation()) == Relation.ALLY) def = true;
            else if (data.relation(n, war.attackerNation()) == Relation.ALLY) att = true;
        }
        return def ? Side.DEFENDER : att ? Side.ATTACKER : Side.NONE;
    }

    // ------------------------------------------------------------------ ending a siege

    public void end(FlagWar war, Outcome outcome) {
        if (!war.finish()) return;
        try { war.cancel(); } catch (IllegalStateException ignored) { /* not scheduled */ }
        active.remove(war.chunk().getKey());
        war.hideAll();
        war.flag().setBlockData(war.original(), false);

        DataManager data = plugin.data();
        ClaimedChunk c = war.chunk();
        Town def = war.defender();
        long a = war.attackerNation(), d = war.defenderNation();
        Nation an = data.nation(a), dn = data.nation(d);
        c.setLastSiege(System.currentTimeMillis());
        data.saveClaim(c); // persisted before any capture effect so an UNCLAIM's DELETE lands afterwards

        String where = "[" + c.getKey().x() + ", " + c.getKey().z() + "]";
        Component msg;
        switch (outcome) {
            case CAPTURED -> msg = applyCapture(war, an, where);
            case DEFENDED -> msg = Msg.c("war.the_flag_at_was",
                    Msg.p("w", where), Msg.p("t", def.getName()));
            case TIMEOUT -> msg = Msg.c("war.the_siege_of_ran",
                    Msg.p("w", where), Msg.p("t", def.getName()));
            case ABANDONED -> msg = Msg.c("war.the_attackers_abandoned_the", Msg.p("w", where));
            default -> msg = Msg.c("war.the_siege_of_was", Msg.p("w", where));
        }
        if (an != null && dn != null) data.broadcastNations(msg, a, d);
        else if (dn != null) data.broadcastNations(msg, d);
    }

    private Component applyCapture(FlagWar war, Nation attackerNation, String where) {
        DataManager data = plugin.data();
        FileConfiguration cfg = plugin.getConfig();
        ClaimedChunk c = war.chunk();
        Town def = war.defender();
        String an = attackerNation == null ? "?" : attackerNation.getName();
        String mode = cfg.getString("flagwar.on-capture", "RAID").toUpperCase();
        switch (mode) {
            case "TRANSFER" -> {
                Town to = war.attackerTown();
                c.setTownId(to.getId());
                c.setRaidedUntil(0);
                c.setRaiderNation(0);
                data.saveClaim(c);
                plugin.regions().createRegion(to, c, true);
                return Msg.c("war.captured_chunk_from",
                        Msg.p("a", an), Msg.p("w", where), Msg.p("t", def.getName()));
            }
            case "UNCLAIM" -> {
                data.unclaim(c);
                return Msg.c("war.chunk_of_was_razed",
                        Msg.p("w", where), Msg.p("t", def.getName()), Msg.p("a", an));
            }
            default -> {
                int minutes = cfg.getInt("flagwar.raid-minutes", 15);
                c.setRaidedUntil(System.currentTimeMillis() + minutes * 60_000L);
                c.setRaiderNation(war.attackerNation());
                data.saveClaim(c);
                plugin.regions().setProtected(def, c, false);
                return Msg.c("war.chunk_of_fell_may",
                        Msg.p("w", where), Msg.p("t", def.getName()), Msg.p("a", an), Msg.p("m", minutes));
            }
        }
    }

    // ------------------------------------------------------------------ cancellation helpers

    public void cancelTown(long townId) {
        for (FlagWar w : new ArrayList<>(active.values())) {
            if (w.defender().getId() == townId || w.attackerTown().getId() == townId) end(w, Outcome.CANCELLED);
        }
    }

    public void cancelNation(long nationId) {
        for (FlagWar w : new ArrayList<>(active.values())) {
            if (w.attackerNation() == nationId || w.defenderNation() == nationId) end(w, Outcome.CANCELLED);
        }
    }

    public void cancelBetween(long a, long b) {
        for (FlagWar w : new ArrayList<>(active.values())) {
            if ((w.attackerNation() == a && w.defenderNation() == b) || (w.attackerNation() == b && w.defenderNation() == a)) {
                end(w, Outcome.CANCELLED);
            }
        }
    }

    public int cancelStartedBy(UUID starter) {
        int n = 0;
        for (FlagWar w : new ArrayList<>(active.values())) {
            if (w.starter().equals(starter)) { end(w, Outcome.CANCELLED); n++; }
        }
        return n;
    }

    /** Server shutdown: remove every banner again. */
    public void shutdown() {
        for (FlagWar w : new ArrayList<>(active.values())) end(w, Outcome.CANCELLED);
    }
}
