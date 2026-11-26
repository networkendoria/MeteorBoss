package com.zyven.meteorboss;

import org.bukkit.Location;


import java.util.List;

/** Telegraflı (önceden uyarı veren) saldırı. */
final class BossAttack {
    final int type; // 1 = yer vuruşu, 2 = boşluk darbesi
    final int total;
    int left;
    final List<Location> centers;
    final double radius;
    final double damage;

    BossAttack(int type, int total, List<Location> centers, double radius, double damage) {
        this.type = type;
        this.total = total;
        this.left = total;
        this.centers = centers;
        this.radius = radius;
        this.damage = damage;
    }
}

