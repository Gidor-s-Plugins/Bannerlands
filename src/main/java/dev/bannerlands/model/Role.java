package dev.bannerlands.model;

public enum Role {
    MAYOR, ASSISTANT, RESIDENT;

    /** true if this role is at least as powerful as {@code min}. */
    public boolean atLeast(Role min) { return ordinal() <= min.ordinal(); }
}
