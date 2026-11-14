package com.zyven.meteorboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Meteor düşüşünü ve krateri yönetir. Folia uyumlu: her şey meteorun düştüğü
 * bölgenin (region) kendi thread'inde çalışır.
 */
public final class MeteorManager {

    private final MeteorBossPlugin plugin;

    /** 2. aşamada boss'u çıkarmak için buraya bağlanacağız. */
    private final List<Consumer<Location>> impactHooks = new CopyOnWriteArrayList<>();

    public MeteorManager(MeteorBossPlugin plugin) {
        this.plugin = plugin;
    }

    public void addImpactHook(Consumer<Location> hook) {
        impactHooks.add(hook);
    }

    /** Hangi thread'den çağrılırsa çağrılsın güvenli. */
    public void launch(World world, int x, int z) {
        Bukkit.getRegionScheduler().run(plugin, world, x >> 4, z >> 4, task -> startFall(world, x, z));
    }

    private void startFall(World world, int x, int z) {
        FileConfiguration cfg = plugin.getConfig();
        int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int fallHeight = Math.max(20, cfg.getInt("fall-height", 90));
        int fallTicks = Math.max(20, cfg.getInt("fall-ticks", 100));
        int radius = craterRadius();
        int startY = Math.min(groundY + fallHeight, world.getMaxHeight() - 1);
        double endY = groundY + 1;

        if (cfg.getBoolean("announce", true)) {
            MiniMessage mm = MiniMessage.miniMessage();
            Component message = mm.deserialize(cfg.getString("messages.incoming", ""));
            if (cfg.getBoolean("announce-coords", true)) {
                String coords = cfg.getString("messages.coords", "")
                        .replace("%x%", String.valueOf(x))
                        .replace("%z%", String.valueOf(z));
                message = message.append(mm.deserialize(coords));
            }
            Bukkit.getServer().broadcast(message);
        }

        Location ground = new Location(world, x + 0.5, endY, z + 0.5);
        int[] tick = {0};

        Bukkit.getRegionScheduler().runAtFixedRate(plugin, ground, task -> {
            tick[0]++;
            double progress = Math.min(1.0, tick[0] / (double) fallTicks);
            double y = startY - (startY - endY) * progress * progress;
            Location head = new Location(world, x + 0.5, y, z + 0.5);

            // Meteorun kafası ve ateş izi
            world.spawnParticle(Particle.FLAME, head, 40, 0.7, 0.7, 0.7, 0.02, null, true);
            world.spawnParticle(Particle.LARGE_SMOKE, head.clone().add(0, 1.5, 0), 15, 0.6, 1.2, 0.6, 0.01, null, true);
            world.spawnParticle(Particle.LAVA, head, 4, 0.5, 0.5, 0.5, 0, null, true);

            // Yerdeki uyarı halkası
            if (tick[0] % 5 == 0) {
                Particle.DustOptions red = new Particle.DustOptions(Color.RED, 2.0f);
                for (int i = 0; i < 24; i++) {
                    double angle = i * (Math.PI * 2 / 24);
                    Location p = new Location(world,
                            x + 0.5 + Math.cos(angle) * (radius + 1),
                            endY + 0.2,
                            z + 0.5 + Math.sin(angle) * (radius + 1));
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, red, true);
                }
            }

            // Düşme sesi
            if (tick[0] % 8 == 0) {
                world.playSound(head, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 6.0f, 0.5f);
            }

            if (tick[0] >= fallTicks) {
                task.cancel();
                impact(world, x, z, groundY);
            }
        }, 1L, 1L);
    }

    private void impact(World world, int x, int z, int groundY) {
        Location center = new Location(world, x + 0.5, groundY + 1, z + 0.5);

        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 6, 2, 1, 2, 0, null, true);
        world.spawnParticle(Particle.FLAME, center, 200, 3, 1.5, 3, 0.15, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, center, 120, 3, 2, 3, 0.1, null, true);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 10.0f, 0.6f);
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 10.0f, 0.7f);

        if (plugin.getConfig().getBoolean("break-blocks", true)) {
            carveCrater(world, x, z, groundY);
        }

        Location craterCenter = new Location(world, x + 0.5, groundY + 1, z + 0.5);
        for (Consumer<Location> hook : impactHooks) {
            hook.accept(craterCenter.clone());
        }
    }

    private void carveCrater(World world, int x, int z, int groundY) {
        int radius = craterRadius();
        int depthMax = radius / 2 + 1;
        int outer = radius + 2;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int bx = x + dx;
                int bz = z + dz;
                // Başka bölgenin (thread'in) bloğuna dokunma, Folia çöker
                if (!Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4)) {
                    continue;
                }
                double d2 = dx * dx + dz * dz;

                if (d2 <= radius * radius) {
                    int depth = (int) Math.round(depthMax * (1.0 - d2 / (radius * radius)));
                    int floorY = groundY - depth;
                    for (int y = groundY + 6; y > floorY; y--) {
                        Block b = world.getBlockAt(bx, y, bz);
                        if (canReplace(b)) {
                            b.setType(Material.AIR, false);
                        }
                    }
                    Block floor = world.getBlockAt(bx, floorY, bz);
                    if (canReplace(floor)) {
                        floor.setType(pickFloor(rnd, d2 <= 1.0), false);
                    }
                } else if (d2 <= outer * outer) {
                    Block top = world.getBlockAt(bx, groundY, bz);
                    if (canReplace(top) && !top.getType().isAir() && rnd.nextDouble() < 0.6) {
                        top.setType(rnd.nextDouble() < 0.7 ? Material.COARSE_DIRT : Material.BLACKSTONE, false);
                    }
                }
            }
        }
    }

    private boolean canReplace(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return true;
        }
        if (type.getHardness() < 0) { // bedrock, barrier, command block vb.
            return false;
        }
        return !(block.getState(false) instanceof Container); // sandık vb. bozulmasın
    }

    private Material pickFloor(ThreadLocalRandom rnd, boolean core) {
        if (core) {
            return Material.MAGMA_BLOCK;
        }
        double r = rnd.nextDouble();
        if (r < 0.5) {
            return Material.BLACKSTONE;
        }
        if (r < 0.85) {
            return Material.COARSE_DIRT;
        }
        return Material.MAGMA_BLOCK;
    }

    private int craterRadius() {
        return Math.max(2, Math.min(12, plugin.getConfig().getInt("crater-radius", 4)));
    }
}
