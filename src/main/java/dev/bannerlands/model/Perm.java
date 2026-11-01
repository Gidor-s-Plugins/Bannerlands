package dev.bannerlands.model;

public enum Perm {
    BREAK(1, "Break"), PLACE(2, "Place"), CHEST(4, "Chests"), DOOR(8, "Doors");

    public static final int ALL = 15;
    private final int bit; private final String label;

    Perm(int bit, String label) { this.bit = bit; this.label = label; }
    public int bit() { return bit; }
    public String label() { return label; }

    public static Perm parse(String s) {
        for (Perm p : values()) {
            if (p.name().equalsIgnoreCase(s) || p.label.equalsIgnoreCase(s)) return p;
        }
        if (s.equalsIgnoreCase("chestaccess")) return CHEST;
        if (s.equalsIgnoreCase("dooraccess")) return DOOR;
        return null;
    }
}
