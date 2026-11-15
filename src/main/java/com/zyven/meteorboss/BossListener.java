package com.zyven.meteorboss;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;

/** Typhon ile ilgili oyun olayları: hasar kuralları, ödül, blok kırmama. */
public final class BossListener implements Listener {

    private final BossManager boss;

    public BossListener(BossManager boss) {
        this.boss = boss;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        boss.onDamage(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDone(EntityDamageEvent e) {
        boss.onDamageDone(e);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        boss.onDeath(e);
    }

    /** Boss hiçbir bloğu bozamaz, değiştiremez (kapı kırma, yumurta ezme vb.). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (boss.isBody(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    /** Suda boğulmuş zombiye dönüşmesin. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (boss.isBody(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (boss.isBody(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    /** Boss yardımcı zombi çağırmasın. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onReinforcement(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        Location home = boss.homeOrNull();
        if (home != null && home.getWorld() == e.getLocation().getWorld()
                && home.distanceSquared(e.getLocation()) < 100 * 100) {
            e.setCancelled(true);
        }
    }
}
