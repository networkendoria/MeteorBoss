package com.zyven.meteorboss;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Boşluk Çukuru'nun hangi blokları nasıl değiştireceğini hesaplar (dünyaya dokunmaz). */
final class ArenaPlanner {

    record Change(int x, int y, int z, Material material, boolean onlyIfSolid) {}

    private ArenaPlanner() {
    }

    static int depthMax(int radius) {
        return Math.max(3, radius / 2);
    }

    /** Parça (chunk) anahtarına göre gruplanmış değişiklik listesi. */
    static Map<Long, List<Change>> plan(int centerX, int centerZ, int groundY, int radius,
                                        long seed, int minY, int maxY) {
        Map<Long, Change> byPos = new HashMap<>();
        int outer = radius + 3;
        int depthMax = depthMax(radius);
        int top = Math.min(maxY - 1, groundY + 10);

        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                int x = centerX + dx;
                int z = centerZ + dz;

                if (dist <= radius) {
                    int floorY = floorY(dist, radius, depthMax, groundY, minY);
                    for (int y = floorY + 1; y <= top; y++) {
                        put(byPos, new Change(x, y, z, Material.AIR, false));
                    }
                    double rnd = rand(seed, x, floorY, z);
                    put(byPos, new Change(x, floorY, z, floorMaterial(dist / radius, rnd), false));
                } else if (dist <= outer) {
                    double fade = 1.0 - (dist - radius) / 3.0;
                    if (rand(seed, x, groundY, z) < 0.75 * fade + 0.15) {
                        double pick = rand(seed + 1, x, groundY, z);
                        Material m = pick < 0.5 ? Material.COARSE_DIRT
                                : pick < 0.85 ? Material.BLACKSTONE : Material.END_STONE;
                        put(byPos, new Change(x, groundY, z, m, true));
                    }
                }
            }
        }

        // Sunak: ortada 3x3 ağlayan obsidyen ve obsidyen
        int altarY = floorY(0, radius, depthMax, groundY, minY);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Material m = (dx == 0 && dz == 0) ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN;
                put(byPos, new Change(centerX + dx, altarY, centerZ + dz, m, false));
            }
        }

        // Altı sütun: obsidyen gövde, tepede End çubuğu
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3 + Math.PI / 6;
            int px = centerX + (int) Math.round(Math.cos(angle) * radius * 0.82);
            int pz = centerZ + (int) Math.round(Math.sin(angle) * radius * 0.82);
            double dist = Math.sqrt((px - centerX) * (px - centerX) + (pz - centerZ) * (pz - centerZ));
            int base = floorY(dist, radius, depthMax, groundY, minY);
            for (int y = base; y <= base + 5; y++) {
                put(byPos, new Change(px, y, pz, Material.OBSIDIAN, false));
            }
            put(byPos, new Change(px, base + 6, pz, Material.END_ROD, false));
        }

        Map<Long, List<Change>> grouped = new HashMap<>();
        for (Change c : byPos.values()) {
            long key = Session.chunkKey(c.x() >> 4, c.z() >> 4);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        return grouped;
    }

    private static int floorY(double dist, int radius, int depthMax, int groundY, int minY) {
        double r = Math.min(1.0, dist / radius);
        int depth = (int) Math.round(depthMax * (1.0 - r * r));
        return Math.max(minY + 2, groundY - depth);
    }

    private static Material floorMaterial(double r, double rnd) {
        if (r < 0.2) {
            return rnd < 0.5 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN;
        }
        if (r < 0.5) {
            return rnd < 0.6 ? Material.OBSIDIAN : rnd < 0.8 ? Material.END_STONE_BRICKS : Material.CRYING_OBSIDIAN;
        }
        if (r < 0.8) {
            return rnd < 0.55 ? Material.END_STONE : rnd < 0.85 ? Material.END_STONE_BRICKS : Material.PURPUR_BLOCK;
        }
        return rnd < 0.5 ? Material.END_STONE : rnd < 0.8 ? Material.BLACKSTONE : Material.MAGMA_BLOCK;
    }

    private static void put(Map<Long, Change> map, Change c) {
        long key = ((long) (c.x() & 0x3FFFFFF) << 38) | ((long) (c.z() & 0x3FFFFFF) << 12) | ((c.y() + 2048) & 0xFFF);
        map.put(key, c);
    }

    /** Aynı girdiye hep aynı 0..1 sayısını veren basit rastgelelik. */
    static double rand(long seed, int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
