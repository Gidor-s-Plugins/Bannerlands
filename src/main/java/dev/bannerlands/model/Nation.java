package dev.bannerlands.model;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Nation {
    private final long id;
    private volatile String name;
    private volatile long capitalTownId;
    private volatile UUID king;
    private volatile boolean friendlyFire;
    private final long createdAt;
    private final Set<Long> towns = ConcurrentHashMap.newKeySet();

    public Nation(long id, String name, long capitalTownId, long createdAt) {
        this.id = id; this.name = name; this.capitalTownId = capitalTownId; this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getCapitalTownId() { return capitalTownId; }
    public void setCapitalTownId(long v) { this.capitalTownId = v; }
    public boolean isFriendlyFire() { return friendlyFire; }
    public void setFriendlyFire(boolean b) { this.friendlyFire = b; }
    /** The player who leads the nation (independent from who owns the capital town). */
    public UUID getKing() { return king; }
    public void setKing(UUID king) { this.king = king; }
    public long getCreatedAt() { return createdAt; }
    public Set<Long> getTowns() { return towns; }
}
