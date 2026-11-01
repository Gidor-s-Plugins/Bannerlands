package dev.bannerlands.model;

import java.util.UUID;

public final class PlayerProfile {
    private final UUID uuid;
    private volatile String name;
    private volatile int townSlots;
    private volatile long lastSeen;

    public PlayerProfile(UUID uuid, String name, int townSlots, long lastSeen) {
        this.uuid = uuid; this.name = name; this.townSlots = townSlots; this.lastSeen = lastSeen;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public int getTownSlots() { return townSlots; }
    public void setTownSlots(int s) { this.townSlots = s; }
    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long t) { this.lastSeen = t; }
}
