package dev.bannerlands.model;

public final class ClaimedChunk {
    private final ChunkKey key;
    private volatile long townId;
    private final long claimedAt;
    private volatile long raidedUntil;
    private volatile long raiderNation;
    private volatile long lastSiege;

    public ClaimedChunk(ChunkKey key, long townId, long claimedAt) {
        this.key = key; this.townId = townId; this.claimedAt = claimedAt;
    }

    public ChunkKey getKey() { return key; }
    public long getTownId() { return townId; }
    public void setTownId(long townId) { this.townId = townId; }
    public long getClaimedAt() { return claimedAt; }
    public long getRaidedUntil() { return raidedUntil; }
    public void setRaidedUntil(long v) { this.raidedUntil = v; }
    public long getRaiderNation() { return raiderNation; }
    public void setRaiderNation(long v) { this.raiderNation = v; }
    public long getLastSiege() { return lastSiege; }
    public void setLastSiege(long v) { this.lastSiege = v; }
    public boolean isRaided() { return raidedUntil > System.currentTimeMillis(); }
}
