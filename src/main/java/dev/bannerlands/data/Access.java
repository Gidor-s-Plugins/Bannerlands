package dev.bannerlands.data;

import dev.bannerlands.model.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/** Pure in-memory permission checks. Nothing in here ever touches the database. */
public final class Access {
    private final DataManager data;

    public Access(DataManager data) { this.data = data; }

    public ClaimedChunk claimAt(Block b) {
        return data.claims.get(new ChunkKey(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4));
    }

    public boolean can(Player p, ClaimedChunk c, Perm perm) {
        if (c == null) return true;
        if (p.hasPermission("bannerlands.admin")) return true;
        Town t = data.town(c.getTownId());
        if (t == null) return true;
        if (c.isRaided() && c.getRaiderNation() != 0 && isInNation(p.getUniqueId(), c.getRaiderNation())) return true;
        return t.has(tier(p.getUniqueId(), t), perm);
    }

    public boolean isInNation(UUID u, long nationId) {
        Set<Long> ids = data.playerTowns.get(u);
        if (ids == null) return false;
        for (long id : ids) {
            Town t = data.town(id);
            if (t != null && t.getNationId() == nationId) return true;
        }
        return false;
    }

    /** Resolve how this player relates to the given town. Enemy beats ally beats neutral. */
    public Tier tier(UUID u, Town t) {
        if (t.getMembers().containsKey(u)) return Tier.MEMBER;
        if (t.getTrusted().contains(u)) return Tier.TRUSTED;
        Set<Long> ids = data.playerTowns.get(u);
        if (ids == null || t.getNationId() == 0) return Tier.NEUTRAL;
        boolean ally = false;
        for (long id : ids) {
            Town pt = data.town(id);
            if (pt == null || pt.getNationId() == 0) continue;
            if (pt.getNationId() == t.getNationId()) { ally = true; continue; }
            Relation r = data.relation(pt.getNationId(), t.getNationId());
            if (r == Relation.ENEMY || r == Relation.WAR) return Tier.ENEMY;
            if (r == Relation.ALLY) ally = true;
        }
        return ally ? Tier.ALLY : Tier.NEUTRAL;
    }

    /**
     * Should PvP between these two players be blocked?
     * Same town -> town setting; same nation -> nation setting; allied nations -> both nations must enable it.
     */
    public boolean pvpProtected(UUID a, UUID b) {
        Set<Long> ta = data.playerTowns.get(a), tb = data.playerTowns.get(b);
        if (ta == null || tb == null) return false;
        for (long x : ta) {
            Town t1 = data.town(x);
            if (t1 == null) continue;
            for (long y : tb) {
                if (x == y) { if (!t1.isFriendlyFire()) return true; continue; }
                Town t2 = data.town(y);
                if (t2 == null) continue;
                long n1 = t1.getNationId(), n2 = t2.getNationId();
                if (n1 == 0 || n2 == 0) continue;
                Nation na = data.nation(n1), nb = data.nation(n2);
                if (na == null || nb == null) continue;
                if (n1 == n2) {
                    if (!na.isFriendlyFire()) return true;
                } else if (data.relation(n1, n2) == Relation.ALLY) {
                    if (!(na.isFriendlyFire() && nb.isFriendlyFire())) return true;
                }
            }
        }
        return false;
    }
}
