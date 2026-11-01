package dev.bannerlands.region;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.util.Lang;
import dev.bannerlands.model.ClaimedChunk;
import dev.bannerlands.model.Town;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Keeps one WorldGuard ProtectedCuboidRegion per claimed chunk ("bannerlands_<cx>_<cz>").
 *
 * Granular permissions (break/place/chests/doors per tier) can't be expressed with WG's member/owner model,
 * so the regions explicitly ALLOW build/interact/use/chest-access and Bannerland's own listeners enforce the
 * rules from memory. The regions still give other plugins and /rg info a proper marker, carry greeting
 * messages, and deny TNT/creeper/fire damage while the chunk is not being raided.
 */
public final class RegionService {
    public static final String PREFIX = "bannerlands_";
    private final BannerlandsPlugin plugin;

    public RegionService(BannerlandsPlugin plugin) { this.plugin = plugin; }

    private RegionManager manager(World w) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w));
    }

    private static String id(ClaimedChunk c) { return PREFIX + c.getKey().x() + "_" + c.getKey().z(); }

    private static ProtectedCuboidRegion cuboid(String id, World w, int cx, int cz) {
        BlockVector3 min = BlockVector3.at(cx << 4, w.getMinHeight(), cz << 4);
        BlockVector3 max = BlockVector3.at((cx << 4) + 15, w.getMaxHeight() - 1, (cz << 4) + 15);
        return new ProtectedCuboidRegion(id, min, max);
    }

    /** True if the chunk touches a WorldGuard region that wasn't created by Bannerlands. */
    public boolean overlapsForeignRegion(World w, int cx, int cz) {
        RegionManager rm = manager(w);
        if (rm == null) return false;
        ApplicableRegionSet set = rm.getApplicableRegions(cuboid("bannerlands_probe", w, cx, cz));
        for (ProtectedRegion r : set) {
            if (!r.getId().startsWith(PREFIX)) return true;
        }
        return false;
    }

    public void createRegion(Town town, ClaimedChunk c, boolean protect) {
        World w = Bukkit.getWorld(c.getKey().world());
        if (w == null) return;
        RegionManager rm = manager(w);
        if (rm == null) return;
        ProtectedCuboidRegion r = cuboid(id(c), w, c.getKey().x(), c.getKey().z());
        r.setPriority(plugin.getConfig().getInt("worldguard.priority", 10));
        applyDomains(r, town);
        applyFlags(r, town, protect);
        rm.addRegion(r); // replaces any region with the same id
    }

    public void removeRegion(ClaimedChunk c) {
        World w = Bukkit.getWorld(c.getKey().world());
        if (w == null) return;
        RegionManager rm = manager(w);
        if (rm != null) rm.removeRegion(id(c));
    }

    /** Toggle explosion/fire protection (used when a chunk becomes raided / the raid ends). */
    public void setProtected(Town town, ClaimedChunk c, boolean protect) {
        World w = Bukkit.getWorld(c.getKey().world());
        if (w == null) return;
        RegionManager rm = manager(w);
        if (rm == null) return;
        ProtectedRegion r = rm.getRegion(id(c));
        if (r == null) createRegion(town, c, protect);
        else applyFlags(r, town, protect);
    }

    /** Re-sync WG owner/member lists after the roster of a town changes. */
    public void syncDomains(Town town) {
        for (ClaimedChunk c : plugin.data().chunksOf(town.getId())) {
            World w = Bukkit.getWorld(c.getKey().world());
            if (w == null) continue;
            RegionManager rm = manager(w);
            if (rm == null) continue;
            ProtectedRegion r = rm.getRegion(id(c));
            if (r != null) applyDomains(r, town);
        }
    }

    /** Called once after start-up: make sure every claim has its region. */
    public void verifyAll() {
        int created = 0;
        for (ClaimedChunk c : plugin.data().claims.values()) {
            Town t = plugin.data().town(c.getTownId());
            World w = Bukkit.getWorld(c.getKey().world());
            if (t == null || w == null) continue;
            RegionManager rm = manager(w);
            if (rm == null) continue;
            ProtectedRegion existing = rm.getRegion(id(c));
            if (existing == null) { createRegion(t, c, !c.isRaided()); created++; }
            else applyFlags(existing, t, !c.isRaided());
        }
        if (created > 0) plugin.getLogger().info("Re-created " + created + " missing WorldGuard regions.");
    }

    private void applyDomains(ProtectedRegion r, Town town) {
        DefaultDomain owners = new DefaultDomain();
        owners.addPlayer(town.getOwner());
        DefaultDomain members = new DefaultDomain();
        town.getMembers().keySet().forEach(members::addPlayer);
        r.setOwners(owners);
        r.setMembers(members);
    }

    private void applyFlags(ProtectedRegion r, Town town, boolean protect) {
        r.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        r.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        r.setFlag(Flags.USE, StateFlag.State.ALLOW);
        r.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        StateFlag.State guard = protect ? StateFlag.State.DENY : StateFlag.State.ALLOW;
        for (StateFlag f : new StateFlag[]{Flags.TNT, Flags.CREEPER_EXPLOSION, Flags.OTHER_EXPLOSION,
                Flags.FIRE_SPREAD, Flags.LAVA_FIRE}) {
            r.setFlag(f, guard);
        }
        r.setFlag(Flags.GREET_MESSAGE, null);
        r.setFlag(Flags.FAREWELL_MESSAGE, null);
    }
}
