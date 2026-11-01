package dev.bannerlands.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Town {
    private final long id;
    private volatile String name;
    private volatile String description = "";
    private volatile String promo = "";
    private final UUID owner;
    private volatile long nationId;
    private volatile boolean friendlyFire;
    private volatile int bonusClaims;
    private final long createdAt;
    private final int[] perms = new int[Tier.values().length];
    private final Map<UUID, Role> members = new ConcurrentHashMap<>();
    private final Set<UUID> trusted = ConcurrentHashMap.newKeySet();

    public Town(long id, String name, UUID owner, long createdAt) {
        this.id = id; this.name = name; this.owner = owner; this.createdAt = createdAt;
        perms[Tier.TRUSTED.ordinal()] = Perm.ALL;
        perms[Tier.MEMBER.ordinal()] = Perm.ALL;
        perms[Tier.ALLY.ordinal()] = Perm.DOOR.bit();
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getPromo() { return promo; }
    public void setPromo(String p) { this.promo = p; }
    public UUID getOwner() { return owner; }
    public long getNationId() { return nationId; }
    public void setNationId(long id) { this.nationId = id; }
    public boolean isFriendlyFire() { return friendlyFire; }
    public void setFriendlyFire(boolean b) { this.friendlyFire = b; }
    /** Extra claimable chunks this town bought with /town bonus. */
    public int getBonusClaims() { return bonusClaims; }
    public void setBonusClaims(int n) { this.bonusClaims = n; }
    public long getCreatedAt() { return createdAt; }
    public Map<UUID, Role> getMembers() { return members; }
    public Set<UUID> getTrusted() { return trusted; }
    public Role role(UUID u) { return members.get(u); }

    public boolean has(Tier tier, Perm perm) { return (perms[tier.ordinal()] & perm.bit()) != 0; }
    public void set(Tier tier, Perm perm, boolean on) {
        int cur = perms[tier.ordinal()];
        perms[tier.ordinal()] = on ? (cur | perm.bit()) : (cur & ~perm.bit());
    }
    public int rawPerms(Tier tier) { return perms[tier.ordinal()]; }
    public void setRawPerms(Tier tier, int v) { perms[tier.ordinal()] = v; }
}
