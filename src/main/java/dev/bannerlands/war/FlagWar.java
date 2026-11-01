package dev.bannerlands.war;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.util.Msg;
import dev.bannerlands.util.Lang;
import dev.bannerlands.model.ClaimedChunk;
import dev.bannerlands.model.Relation;
import dev.bannerlands.model.Town;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One live siege of one claimed chunk. Runs once per second on the main thread:
 * counts attackers / defenders around the banner, moves the capture percentage, updates the BossBar.
 */
public final class FlagWar extends BukkitRunnable {
    private final BannerlandsPlugin plugin;
    private final FlagWarManager manager;
    private final ClaimedChunk chunk;
    private final Town defender;
    private final Town attackerTown;
    private final long attackerNation, defenderNation;
    private final UUID starter;
    private final Block flag;
    private final BlockData original;

    private final BossBar bar = BossBar.bossBar(Component.text("Flag war"), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
    private final Set<UUID> viewers = new HashSet<>();
    private final long startedAt = System.currentTimeMillis();

    private final double radius, secondsToCapture, maxMultiplier;
    private final long limitMs;
    private final int abandonSeconds;

    private double progress;
    private int idleSeconds;
    private boolean finished;

    FlagWar(BannerlandsPlugin plugin, FlagWarManager manager, ClaimedChunk chunk, Town defender, Town attackerTown,
            UUID starter, Block flag, BlockData original) {
        this.plugin = plugin; this.manager = manager; this.chunk = chunk;
        this.defender = defender; this.attackerTown = attackerTown;
        this.attackerNation = attackerTown.getNationId();
        this.defenderNation = defender.getNationId();
        this.starter = starter; this.flag = flag; this.original = original;
        var cfg = plugin.getConfig();
        this.radius = cfg.getDouble("flagwar.capture-radius", 8);
        this.secondsToCapture = Math.max(10, cfg.getDouble("flagwar.capture-seconds", 300));
        this.maxMultiplier = Math.max(1, cfg.getDouble("flagwar.max-attacker-multiplier", 4));
        this.limitMs = cfg.getLong("flagwar.time-limit-minutes", 25) * 60_000L;
        this.abandonSeconds = cfg.getInt("flagwar.idle-abandon-seconds", 60);
    }

    public ClaimedChunk chunk() { return chunk; }
    public Town defender() { return defender; }
    public Town attackerTown() { return attackerTown; }
    public long attackerNation() { return attackerNation; }
    public long defenderNation() { return defenderNation; }
    public UUID starter() { return starter; }
    public Block flag() { return flag; }
    public BlockData original() { return original; }
    public double progress() { return progress; }

    public boolean isFlag(Block b) {
        return b.getX() == flag.getX() && b.getY() == flag.getY() && b.getZ() == flag.getZ()
                && b.getWorld().equals(flag.getWorld());
    }

    boolean finish() {
        if (finished) return false;
        finished = true;
        return true;
    }

    void hideAll() {
        for (UUID u : viewers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
        }
        viewers.clear();
    }

    @Override
    public void run() {
        if (finished) return;

        if (System.currentTimeMillis() - startedAt > limitMs) { manager.end(this, FlagWarManager.Outcome.TIMEOUT); return; }
        if (plugin.data().relation(attackerNation, defenderNation) != Relation.WAR
                || plugin.data().town(defender.getId()) == null
                || plugin.data().claims.get(chunk.getKey()) != chunk
                || chunk.getTownId() != defender.getId()) {
            manager.end(this, FlagWarManager.Outcome.CANCELLED);
            return;
        }

        Location center = flag.getLocation().add(0.5, 0.5, 0.5);
        int att = 0, def = 0;
        for (Player p : flag.getWorld().getNearbyPlayers(center, radius, 6)) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            switch (manager.side(p, this)) {
                case ATTACKER -> att++;
                case DEFENDER -> def++;
                default -> { }
            }
        }

        double base = 100.0 / secondsToCapture; // percent per second for ONE attacker
        String state;
        BossBar.Color color;
        if (att > 0 && def == 0) {
            progress += base * Math.min(att, maxMultiplier);
            state = "CAPTURING";
            color = BossBar.Color.RED;
            idleSeconds = 0;
        } else if (att > 0) {
            state = "CONTESTED";
            color = BossBar.Color.YELLOW;
            idleSeconds = 0;
        } else if (def > 0) {
            progress -= base * 2;
            state = "RECLAIMING";
            color = BossBar.Color.BLUE;
            idleSeconds++;
        } else {
            progress -= base * 0.5;
            state = "UNATTENDED";
            color = BossBar.Color.WHITE;
            idleSeconds++;
        }
        progress = Math.max(0, Math.min(100, progress));

        if (progress <= 0 && idleSeconds >= abandonSeconds) { manager.end(this, FlagWarManager.Outcome.ABANDONED); return; }

        bar.name(Msg.raw("war.bar", Msg.p("t", defender.getName()), Msg.p("x", chunk.getKey().x()), Msg.p("z", chunk.getKey().z()),
                Msg.p("pct", (int) progress), Msg.p("state", Lang.get("war.state." + state.toLowerCase())),
                Msg.p("att", att), Msg.p("def", def)));
        bar.progress((float) (progress / 100.0));
        bar.color(color);
        updateViewers(center);

        if (progress >= 100) manager.end(this, FlagWarManager.Outcome.CAPTURED);
    }

    /** Show the bar to everyone near the flag plus every online member of the defending town. */
    private void updateViewers(Location center) {
        Set<UUID> want = new HashSet<>();
        for (Player p : flag.getWorld().getNearbyPlayers(center, radius * 3, 64)) want.add(p.getUniqueId());
        for (UUID u : defender.getMembers().keySet()) if (Bukkit.getPlayer(u) != null) want.add(u);
        for (UUID u : want) {
            if (viewers.add(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.showBossBar(bar);
            }
        }
        viewers.removeIf(u -> {
            if (want.contains(u)) return false;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
            return true;
        });
    }
}
