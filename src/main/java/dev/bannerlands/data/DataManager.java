package dev.bannerlands.data;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.model.*;
import dev.bannerlands.storage.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single source of truth at runtime. Everything is held in ConcurrentHashMaps; the database is only
 * read once at start-up and written to asynchronously afterwards (write-behind).
 */
public final class DataManager {
    private final BannerlandsPlugin plugin;
    private final Database db;

    public final Map<Long, Town> towns = new ConcurrentHashMap<>();
    private final Map<String, Town> townNames = new ConcurrentHashMap<>();
    public final Map<Long, Nation> nations = new ConcurrentHashMap<>();
    private final Map<String, Nation> nationNames = new ConcurrentHashMap<>();
    public final Map<ChunkKey, ClaimedChunk> claims = new ConcurrentHashMap<>();
    public final Map<UUID, Set<Long>> playerTowns = new ConcurrentHashMap<>();
    public final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    public final Map<Long, TownInvite> invites = new ConcurrentHashMap<>();
    /** from-nation -> (to-nation -> directed relation). Only ALLY/ENEMY/WAR are stored. */
    private final Map<Long, Map<Long, Relation>> relations = new ConcurrentHashMap<>();

    private final AtomicLong townSeq = new AtomicLong(), nationSeq = new AtomicLong(), inviteSeq = new AtomicLong();

    public DataManager(BannerlandsPlugin plugin, Database db) { this.plugin = plugin; this.db = db; }

    // ------------------------------------------------------------------ loading

    public void loadAll() {
        db.each("SELECT * FROM towns", rs -> {
            Town t = new Town(rs.getLong("id"), rs.getString("name"), UUID.fromString(rs.getString("owner")), rs.getLong("created_at"));
            t.setDescription(rs.getString("description"));
            t.setPromo(rs.getString("promo"));
            t.setNationId(rs.getLong("nation_id"));
            t.setFriendlyFire(rs.getInt("friendly_fire") == 1);
            t.setBonusClaims(rs.getInt("bonus_claims"));
            for (Tier tier : Tier.values()) t.setRawPerms(tier, rs.getInt("perm_" + tier.name().toLowerCase(Locale.ROOT)));
            towns.put(t.getId(), t);
            townNames.put(t.getName().toLowerCase(Locale.ROOT), t);
        });
        db.each("SELECT * FROM town_members", rs -> {
            Town t = towns.get(rs.getLong("town_id"));
            if (t == null) return;
            UUID u = UUID.fromString(rs.getString("uuid"));
            t.getMembers().put(u, Role.valueOf(rs.getString("role")));
            playerTowns.computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet()).add(t.getId());
        });
        db.each("SELECT * FROM town_trusted", rs -> {
            Town t = towns.get(rs.getLong("town_id"));
            if (t != null) t.getTrusted().add(UUID.fromString(rs.getString("uuid")));
        });
        db.each("SELECT * FROM nations", rs -> {
            Nation n = new Nation(rs.getLong("id"), rs.getString("name"), rs.getLong("capital_town_id"), rs.getLong("created_at"));
            n.setFriendlyFire(rs.getInt("friendly_fire") == 1);
            nations.put(n.getId(), n);
            nationNames.put(n.getName().toLowerCase(Locale.ROOT), n);
        });
        for (Town t : towns.values()) {
            if (t.getNationId() == 0) continue;
            Nation n = nations.get(t.getNationId());
            if (n != null) n.getTowns().add(t.getId()); else t.setNationId(0);
        }
        db.each("SELECT * FROM nation_relations", rs ->
                relations.computeIfAbsent(rs.getLong("from_nation"), k -> new ConcurrentHashMap<>())
                        .put(rs.getLong("to_nation"), Relation.valueOf(rs.getString("type"))));
        db.each("SELECT * FROM chunk_claims", rs -> {
            ChunkKey key = new ChunkKey(UUID.fromString(rs.getString("world")), rs.getInt("chunk_x"), rs.getInt("chunk_z"));
            ClaimedChunk c = new ClaimedChunk(key, rs.getLong("town_id"), rs.getLong("claimed_at"));
            c.setRaidedUntil(rs.getLong("raided_until"));
            c.setRaiderNation(rs.getLong("raider_nation"));
            c.setLastSiege(rs.getLong("last_siege"));
            claims.put(key, c);
        });
        db.each("SELECT * FROM town_invites", rs -> {
            TownInvite i = new TownInvite(rs.getLong("id"), rs.getLong("nation_id"), rs.getLong("town_id"),
                    UUID.fromString(rs.getString("sender")), rs.getLong("created_at"), rs.getInt("delivered") == 1);
            invites.put(i.getId(), i);
        });
        db.each("SELECT * FROM player_profiles", rs -> {
            UUID u = UUID.fromString(rs.getString("uuid"));
            profiles.put(u, new PlayerProfile(u, rs.getString("name"), rs.getInt("town_slots"), rs.getLong("last_seen")));
        });
        townSeq.set(towns.keySet().stream().mapToLong(Long::longValue).max().orElse(0));
        nationSeq.set(nations.keySet().stream().mapToLong(Long::longValue).max().orElse(0));
        inviteSeq.set(invites.keySet().stream().mapToLong(Long::longValue).max().orElse(0));
    }

    // ------------------------------------------------------------------ lookups

    public Town town(long id) { return towns.get(id); }
    public Town townByName(String name) { return townNames.get(name.toLowerCase(Locale.ROOT)); }
    public Nation nation(long id) { return nations.get(id); }
    public Nation nationByName(String name) { return nationNames.get(name.toLowerCase(Locale.ROOT)); }

    public int ownedCount(UUID u) {
        int n = 0;
        for (Town t : towns.values()) if (t.getOwner().equals(u)) n++;
        return n;
    }

    public int claimCount(long townId) {
        int n = 0;
        for (ClaimedChunk c : claims.values()) if (c.getTownId() == townId) n++;
        return n;
    }

    /** Max chunks a town may own: base limit (+ nation bonus), capped, plus the bonus chunks the town bought. */
    public int claimLimit(Town t) {
        var cfg = plugin.getConfig();
        int limit = cfg.getInt("towns.base-claims", 30);
        limit += t.getMembers().size() * cfg.getInt("towns.claims-per-member", 0);
        if (t.getNationId() != 0) limit += cfg.getInt("nations.claim-bonus", 10);
        // bought bonus chunks are added on top of the capped free limit
        return Math.min(limit, cfg.getInt("towns.max-claims", 64)) + t.getBonusClaims();
    }

    public List<ClaimedChunk> chunksOf(long townId) {
        List<ClaimedChunk> out = new ArrayList<>();
        for (ClaimedChunk c : claims.values()) if (c.getTownId() == townId) out.add(c);
        return out;
    }

    /** The town a command should act on: the town you stand in (if you belong to it), otherwise your oldest town. */
    public Town activeTown(Player p) {
        UUID u = p.getUniqueId();
        ClaimedChunk c = claims.get(new ChunkKey(p.getWorld().getUID(), p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4));
        if (c != null) {
            Town t = towns.get(c.getTownId());
            if (t != null && t.getMembers().containsKey(u)) return t;
        }
        Set<Long> ids = playerTowns.get(u);
        if (ids == null || ids.isEmpty()) return null;
        return ids.stream().sorted().map(towns::get).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public Relation directed(long from, long to) {
        Map<Long, Relation> m = relations.get(from);
        Relation r = m == null ? null : m.get(to);
        return r == null ? Relation.NEUTRAL : r;
    }

    /** Effective relation: WAR/ENEMY if either side declared it, ALLY only when both sides agreed. */
    public Relation relation(long a, long b) {
        if (a == b || a == 0 || b == 0) return Relation.NEUTRAL;
        Relation r1 = directed(a, b), r2 = directed(b, a);
        if (r1 == Relation.WAR || r2 == Relation.WAR) return Relation.WAR;
        if (r1 == Relation.ENEMY || r2 == Relation.ENEMY) return Relation.ENEMY;
        if (r1 == Relation.ALLY && r2 == Relation.ALLY) return Relation.ALLY;
        return Relation.NEUTRAL;
    }

    public void setRelation(long from, long to, Relation r) {
        if (r == Relation.NEUTRAL) {
            Map<Long, Relation> m = relations.get(from);
            if (m != null) m.remove(to);
            db.update("DELETE FROM nation_relations WHERE from_nation=? AND to_nation=?", from, to);
        } else {
            relations.computeIfAbsent(from, k -> new ConcurrentHashMap<>()).put(to, r);
            db.update("REPLACE INTO nation_relations (from_nation,to_nation,type) VALUES (?,?,?)", from, to, r.name());
        }
    }

    public PlayerProfile profile(UUID u, String name) {
        return profiles.computeIfAbsent(u, k -> {
            PlayerProfile pr = new PlayerProfile(u, name, plugin.getConfig().getInt("towns.starting-slots", 1), System.currentTimeMillis());
            saveProfile(pr);
            return pr;
        });
    }

    public void touchProfile(Player p) {
        PlayerProfile pr = profile(p.getUniqueId(), p.getName());
        pr.setName(p.getName());
        pr.setLastSeen(System.currentTimeMillis());
        saveProfile(pr);
    }

    public UUID findPlayer(String name) {
        for (PlayerProfile pr : profiles.values()) if (pr.getName().equalsIgnoreCase(name)) return pr.getUuid();
        return null;
    }

    public String nameOf(UUID u) {
        PlayerProfile pr = profiles.get(u);
        return pr != null ? pr.getName() : u.toString().substring(0, 8);
    }

    /** Send a message to every online member of the given nations / towns (deduplicated). */
    public void broadcastNations(Component msg, long... nationIds) {
        Set<UUID> sent = new HashSet<>();
        for (long nid : nationIds) {
            Nation n = nations.get(nid);
            if (n == null) continue;
            for (long tid : n.getTowns()) broadcastTown(msg, towns.get(tid), sent);
        }
    }

    public void broadcastTown(Component msg, Town t, Set<UUID> alreadySent) {
        if (t == null) return;
        for (UUID u : t.getMembers().keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && alreadySent.add(u)) p.sendMessage(msg);
        }
    }

    // ------------------------------------------------------------------ persistence helpers

    public void saveTown(Town t) {
        db.update("REPLACE INTO towns (id,name,description,promo,owner,nation_id,friendly_fire,perm_neutral,perm_trusted,perm_member,perm_ally,perm_enemy,created_at,bonus_claims) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t.getId(), t.getName(), t.getDescription(), t.getPromo(), t.getOwner(), t.getNationId(), t.isFriendlyFire(),
                t.rawPerms(Tier.NEUTRAL), t.rawPerms(Tier.TRUSTED), t.rawPerms(Tier.MEMBER), t.rawPerms(Tier.ALLY), t.rawPerms(Tier.ENEMY),
                t.getCreatedAt(), t.getBonusClaims());
    }

    public void saveNation(Nation n) {
        db.update("REPLACE INTO nations (id,name,capital_town_id,friendly_fire,created_at) VALUES (?,?,?,?,?)",
                n.getId(), n.getName(), n.getCapitalTownId(), n.isFriendlyFire(), n.getCreatedAt());
    }

    public void saveClaim(ClaimedChunk c) {
        db.update("REPLACE INTO chunk_claims (world,chunk_x,chunk_z,town_id,claimed_at,raided_until,raider_nation,last_siege) VALUES (?,?,?,?,?,?,?,?)",
                c.getKey().world(), c.getKey().x(), c.getKey().z(), c.getTownId(), c.getClaimedAt(),
                c.getRaidedUntil(), c.getRaiderNation(), c.getLastSiege());
    }

    public void saveInvite(TownInvite i) {
        db.update("REPLACE INTO town_invites (id,nation_id,town_id,sender,created_at,delivered) VALUES (?,?,?,?,?,?)",
                i.getId(), i.getNationId(), i.getTownId(), i.getSender(), i.getCreatedAt(), i.isDelivered());
    }

    public void saveProfile(PlayerProfile p) {
        db.update("REPLACE INTO player_profiles (uuid,name,town_slots,last_seen) VALUES (?,?,?,?)",
                p.getUuid(), p.getName(), p.getTownSlots(), p.getLastSeen());
    }

    private void saveMember(Town t, UUID u, Role r) {
        db.update("REPLACE INTO town_members (town_id,uuid,role) VALUES (?,?,?)", t.getId(), u, r.name());
    }

    // ------------------------------------------------------------------ towns

    public Town createTown(String name, UUID owner) {
        Town t = new Town(townSeq.incrementAndGet(), name, owner, System.currentTimeMillis());
        t.getMembers().put(owner, Role.MAYOR);
        towns.put(t.getId(), t);
        townNames.put(name.toLowerCase(Locale.ROOT), t);
        playerTowns.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(t.getId());
        saveTown(t);
        saveMember(t, owner, Role.MAYOR);
        return t;
    }

    public void deleteTown(Town t) {
        plugin.wars().cancelTown(t.getId());
        for (ClaimedChunk c : chunksOf(t.getId())) {
            plugin.regions().removeRegion(c);
            claims.remove(c.getKey());
        }
        db.update("DELETE FROM chunk_claims WHERE town_id=?", t.getId());
        if (t.getNationId() != 0) {
            Nation n = nations.get(t.getNationId());
            if (n != null) {
                if (n.getCapitalTownId() == t.getId()) deleteNation(n);
                else { n.getTowns().remove(t.getId()); }
            }
        }
        for (UUID u : t.getMembers().keySet()) {
            Set<Long> s = playerTowns.get(u);
            if (s != null) s.remove(t.getId());
        }
        for (TownInvite i : new ArrayList<>(invites.values())) if (i.getTownId() == t.getId()) removeInvite(i);
        towns.remove(t.getId());
        townNames.remove(t.getName().toLowerCase(Locale.ROOT));
        db.update("DELETE FROM towns WHERE id=?", t.getId());
        db.update("DELETE FROM town_members WHERE town_id=?", t.getId());
        db.update("DELETE FROM town_trusted WHERE town_id=?", t.getId());
    }

    public void addMember(Town t, UUID u, Role r) {
        t.getMembers().put(u, r);
        playerTowns.computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet()).add(t.getId());
        saveMember(t, u, r);
        plugin.regions().syncDomains(t);
    }

    public void setRole(Town t, UUID u, Role r) {
        t.getMembers().put(u, r);
        saveMember(t, u, r);
    }

    public void removeMember(Town t, UUID u) {
        t.getMembers().remove(u);
        Set<Long> s = playerTowns.get(u);
        if (s != null) s.remove(t.getId());
        db.update("DELETE FROM town_members WHERE town_id=? AND uuid=?", t.getId(), u);
        plugin.regions().syncDomains(t);
    }

    public void trust(Town t, UUID u, boolean on) {
        if (on) {
            t.getTrusted().add(u);
            db.update("REPLACE INTO town_trusted (town_id,uuid) VALUES (?,?)", t.getId(), u);
        } else {
            t.getTrusted().remove(u);
            db.update("DELETE FROM town_trusted WHERE town_id=? AND uuid=?", t.getId(), u);
        }
    }

    // ------------------------------------------------------------------ claims

    public ClaimedChunk claim(Town t, UUID world, int x, int z) {
        ClaimedChunk c = new ClaimedChunk(new ChunkKey(world, x, z), t.getId(), System.currentTimeMillis());
        claims.put(c.getKey(), c);
        saveClaim(c);
        plugin.regions().createRegion(t, c, true);
        return c;
    }

    public void unclaim(ClaimedChunk c) {
        claims.remove(c.getKey());
        plugin.regions().removeRegion(c);
        db.update("DELETE FROM chunk_claims WHERE world=? AND chunk_x=? AND chunk_z=?", c.getKey().world(), c.getKey().x(), c.getKey().z());
    }

    /** Called periodically: ends raids whose timer ran out and restores explosion/fire protection. */
    public void expireRaids() {
        long now = System.currentTimeMillis();
        for (ClaimedChunk c : claims.values()) {
            if (c.getRaidedUntil() != 0 && c.getRaidedUntil() <= now) {
                c.setRaidedUntil(0);
                c.setRaiderNation(0);
                saveClaim(c);
                Town t = towns.get(c.getTownId());
                if (t != null) plugin.regions().setProtected(t, c, true);
            }
        }
    }

    // ------------------------------------------------------------------ nations

    public Nation createNation(String name, Town capital) {
        Nation n = new Nation(nationSeq.incrementAndGet(), name, capital.getId(), System.currentTimeMillis());
        nations.put(n.getId(), n);
        nationNames.put(name.toLowerCase(Locale.ROOT), n);
        saveNation(n);
        setTownNation(capital, n);
        return n;
    }

    /** Put a town into a nation (or remove it with {@code n == null}). A town is in at most one nation. */
    public void setTownNation(Town t, Nation n) {
        Nation old = nations.get(t.getNationId());
        if (old != null) old.getTowns().remove(t.getId());
        t.setNationId(n == null ? 0 : n.getId());
        if (n != null) n.getTowns().add(t.getId());
        saveTown(t);
    }

    public void deleteNation(Nation n) {
        plugin.wars().cancelNation(n.getId());
        for (long tid : new ArrayList<>(n.getTowns())) {
            Town t = towns.get(tid);
            if (t != null) { t.setNationId(0); saveTown(t); }
        }
        n.getTowns().clear();
        relations.remove(n.getId());
        for (Map<Long, Relation> m : relations.values()) m.remove(n.getId());
        db.update("DELETE FROM nation_relations WHERE from_nation=? OR to_nation=?", n.getId(), n.getId());
        for (TownInvite i : new ArrayList<>(invites.values())) if (i.getNationId() == n.getId()) removeInvite(i);
        nations.remove(n.getId());
        nationNames.remove(n.getName().toLowerCase(Locale.ROOT));
        db.update("DELETE FROM nations WHERE id=?", n.getId());
    }

    // ------------------------------------------------------------------ invites

    public TownInvite createInvite(Nation n, Town target, UUID sender) {
        TownInvite i = new TownInvite(inviteSeq.incrementAndGet(), n.getId(), target.getId(), sender, System.currentTimeMillis(), false);
        invites.put(i.getId(), i);
        saveInvite(i);
        return i;
    }

    public void removeInvite(TownInvite i) {
        invites.remove(i.getId());
        db.update("DELETE FROM town_invites WHERE id=?", i.getId());
    }
}
