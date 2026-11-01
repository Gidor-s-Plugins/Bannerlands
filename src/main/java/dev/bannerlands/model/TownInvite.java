package dev.bannerlands.model;

import java.util.UUID;

/** A nation's invitation for a town to join it. Stored in the DB so it survives logouts and restarts. */
public final class TownInvite {
    private final long id;
    private final long nationId;
    private final long townId;
    private final UUID sender;
    private final long createdAt;
    private volatile boolean delivered;

    public TownInvite(long id, long nationId, long townId, UUID sender, long createdAt, boolean delivered) {
        this.id = id; this.nationId = nationId; this.townId = townId;
        this.sender = sender; this.createdAt = createdAt; this.delivered = delivered;
    }

    public long getId() { return id; }
    public long getNationId() { return nationId; }
    public long getTownId() { return townId; }
    public UUID getSender() { return sender; }
    public long getCreatedAt() { return createdAt; }
    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean d) { this.delivered = d; }
}
