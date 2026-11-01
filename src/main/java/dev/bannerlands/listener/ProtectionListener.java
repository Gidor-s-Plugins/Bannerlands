package dev.bannerlands.listener;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.data.Access;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.model.ClaimedChunk;
import dev.bannerlands.model.Perm;
import dev.bannerlands.model.Town;
import dev.bannerlands.util.Msg;
import dev.bannerlands.war.FlagWar;
import dev.bannerlands.war.FlagWarManager;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Enforces claims. Every check is a handful of ConcurrentHashMap lookups: zero database access. */
public final class ProtectionListener implements Listener {
    private static final Set<Material> CONTAINERS = EnumSet.noneOf(Material.class);

    static {
        Collections.addAll(CONTAINERS, Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.FURNACE,
                Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER, Material.DISPENSER, Material.DROPPER,
                Material.BREWING_STAND, Material.LECTERN, Material.CHISELED_BOOKSHELF);
        for (Material m : Material.values()) if (m.name().endsWith("SHULKER_BOX")) CONTAINERS.add(m);
    }

    private final BannerlandsPlugin plugin;
    private final DataManager data;
    private final Access access;

    public ProtectionListener(BannerlandsPlugin plugin) {
        this.plugin = plugin;
        this.data = plugin.data();
        this.access = plugin.access();
    }

    private static boolean isDoorLike(Material m) {
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || m == Material.LEVER;
    }

    private void deny(Cancellable e, Player p, ClaimedChunk c) {
        e.setCancelled(true);
        Town t = data.town(c.getTownId());
        p.sendActionBar(Msg.raw("protect.this_land_belongs_to", Msg.p("t", t == null ? "?" : t.getName())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        ClaimedChunk c = access.claimAt(b);
        if (c == null) return;
        Player p = e.getPlayer();
        FlagWar war = plugin.wars().at(c.getKey());
        if (war != null && war.isFlag(b)) {
            e.setCancelled(true);
            if (plugin.wars().side(p, war) == FlagWarManager.Side.DEFENDER) {
                plugin.wars().end(war, FlagWarManager.Outcome.DEFENDED); // defenders tear the banner down
            } else {
                p.sendActionBar(Msg.raw("protect.only_defenders_can_destroy"));
            }
            return;
        }
        if (!access.can(p, c, Perm.BREAK)) deny(e, p, c);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ClaimedChunk c = access.claimAt(e.getBlockPlaced());
        if (c != null && !access.can(e.getPlayer(), c, Perm.PLACE)) deny(e, e.getPlayer(), c);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        ClaimedChunk c = access.claimAt(e.getBlock());
        if (c != null && !access.can(e.getPlayer(), c, Perm.PLACE)) deny(e, e.getPlayer(), c);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        ClaimedChunk c = access.claimAt(e.getBlock());
        if (c != null && !access.can(e.getPlayer(), c, Perm.BREAK)) deny(e, e.getPlayer(), c);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Material m = b.getType();
        Perm need;
        if (CONTAINERS.contains(m)) need = Perm.CHEST;
        else if (isDoorLike(m)) need = Perm.DOOR;
        else return;
        ClaimedChunk c = access.claimAt(b);
        if (c == null) return;
        if (!access.can(e.getPlayer(), c, need)) {
            e.setCancelled(true);
            Town t = data.town(c.getTownId());
            e.getPlayer().sendActionBar(Msg.raw("protect.you_can_t_use", Msg.p("t", t == null ? "?" : t.getName())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = resolvePlayer(e.getRemover());
        if (p == null) return;
        ClaimedChunk c = access.claimAt(e.getEntity().getLocation().getBlock());
        if (c != null && !access.can(p, c, Perm.BREAK)) deny(e, p, c);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::shielded);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::shielded);
    }

    /** Claimed land is immune to explosions unless it is currently being raided. */
    private boolean shielded(Block b) {
        ClaimedChunk c = access.claimAt(b);
        return c != null && !c.isRaided();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (access.pvpProtected(attacker.getUniqueId(), victim.getUniqueId())) {
            e.setCancelled(true);
            attacker.sendActionBar(Msg.raw("protect.friendly_fire_is_disabled"));
        }
    }

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
