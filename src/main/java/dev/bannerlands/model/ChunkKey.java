package dev.bannerlands.model;

import java.util.UUID;

public record ChunkKey(UUID world, int x, int z) { }
