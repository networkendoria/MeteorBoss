package com.zyven.meteorboss;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.zyven.meteorboss.TyphonModel.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Typhon: zombi tabanlı, görünmez bir can kutusu ve etrafında blok parçalarından
 * (display) oluşan dev bir görünüm. Resource pack gerekmez.
 * Folia: boss'un döngüsü zombinin kendi thread'inde (entity scheduler) çalışır.
 */
public final class BossManager {

    private static final Set<DamageCause> IMMUNE = EnumSet.of(
            DamageCause.SUFFOCATION, DamageCause.DROWNING, DamageCause.FALL, DamageCause.CRAMMING,
            DamageCause.FLY_INTO_WALL, DamageCause.FREEZE, DamageCause.DRYOUT);

    private static final Set<DamageCause> TRAP = EnumSet.of(
            DamageCause.LAVA, DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.HOT_FLOOR,
            DamageCause.CONTACT, DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION,
            DamageCause.MAGIC, DamageCause.POISON, DamageCause.WITHER, DamageCause.LIGHTNING,
            DamageCause.DRAGON_BREATH, DamageCause.FALLING_BLOCK);

    private final MeteorBossPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private volatile BossState boss;
    private final BossRewards rewards;

    public BossManager(MeteorBossPlugin plugin) {
        this.plugin = plugin;
        this.rewards = new BossRewards(plugin);
    }

    public boolean isAlive() {
        return boss != null;
    }

    // ------------------------------------------------------------------ çıkış

    /** Çukur hazır olunca EventManager tarafından çağrılır. */
    public void spawn(Session s) {
        World world = Bukkit.getWorld(s.worldName);
        if (world == null) {
            return;
        }
        Bukkit.getRegionScheduler().run(plugin, world, s.centerX >> 4, s.centerZ >> 4, t -> doSpawn(s, world));
    }

    private void doSpawn(Session s, World world) {
        BossState b = null;
        try {
            if (boss != null) {
                return;
            }
            FileConfiguration cfg = plugin.getConfig();
            int altarY = ArenaPlanner.altarY(s.groundY, s.radius, world.getMinHeight());
            Location home = new Location(world, s.centerX + 0.5, altarY + 1, s.centerZ + 0.5);
            BossState nb = new BossState(s, world, home);
            b = nb;

            double base = cfg.getDouble("boss.health", 600.0);
            double perPlayer = cfg.getDouble("boss.health-per-player", 100.0);
            int online = Math.max(1, Bukkit.getOnlinePlayers().size());
            nb.maxHealth = Math.min(1024.0, base + perPlayer * (online - 1));
            nb.healCap = nb.maxHealth * cfg.getDouble("boss.heal-cap-percent", 50.0) / 100.0;
            nb.baseSpeed = cfg.getDouble("boss.speed", 0.27);

            Zombie z = world.spawn(home, Zombie.class, zz -> setup(zz, nb, cfg));
            nb.body = z;
            nb.bodyId = z.getUniqueId();

            int level = switch (cfg.getString("boss.model-quality", "high").toLowerCase()) {
                case "low" -> 0;
                case "medium" -> 1;
                default -> 2;
            };
            for (Part pt : PARTS) {
                if (pt.detail() > level) {
                    continue;
                }
                BlockDisplay d = world.spawn(home, BlockDisplay.class, dd -> {
                    dd.setBlock(pt.mat().createBlockData());
                    dd.setTransformation(BossPose.transform(pt, nb, 0.05));
                    dd.setPersistent(false);
                    dd.setInterpolationDuration(4);
                    dd.setTeleportDuration(3);
                    dd.setViewRange(3.0f);
                    if (pt.kind() != 0) {
                        dd.setBrightness(new Display.Brightness(15, 15));
                    }
                });
                nb.parts.add(pt);
                nb.displays.add(d);
            }

            nb.bar = BossBar.bossBar(barTitle(nb), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            boss = nb;
            plugin.log("Typhon çıkıyor (can: " + (int) nb.maxHealth + ", parça: " + nb.displays.size() + ").");

            world.playSound(home, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 12.0f, 0.5f);
            z.getScheduler().runAtFixedRate(plugin, task -> tick(nb, task), () -> finishBoss(nb, false), 1L, 1L);
        } catch (Throwable e) {
            plugin.logError("Boss çıkarılamadı", e);
            if (b != null) {
                finishBoss(b, false);
            }
        }
    }

    private void setup(Zombie z, BossState b, FileConfiguration cfg) {
        setBase(z, Attribute.MAX_HEALTH, b.maxHealth);
        z.setHealth(b.maxHealth);
        setBase(z, Attribute.ATTACK_DAMAGE, cfg.getDouble("boss.melee-damage", 8.0));
        setBase(z, Attribute.MOVEMENT_SPEED, b.baseSpeed);
        setBase(z, Attribute.FOLLOW_RANGE, 60.0);
        setBase(z, Attribute.KNOCKBACK_RESISTANCE, 0.8);
        setBase(z, Attribute.SCALE, cfg.getDouble("boss.hitbox-scale", 3.2));
        z.setBaby(false);
        z.setCanBreakDoors(false);
        z.setShouldBurnInDay(false);
        z.setRemoveWhenFarAway(false);
        z.setPersistent(false);
        z.setCanPickupItems(false);
        z.setInvisible(true);
        z.setSilent(true); // zombi sesi yok, Typhon'un kendi sesleri var
        z.setAI(false);
        z.setInvulnerable(true);
        z.customName(mm.deserialize("<light_purple>Typhon"));
        z.setCustomNameVisible(false);
        if (z.getEquipment() != null) {
            z.getEquipment().clear();
        }
    }

    private static void setBase(Zombie z, Attribute attribute, double value) {
        AttributeInstance inst = z.getAttribute(attribute);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private Component barTitle(BossState b) {
        return mm.deserialize(plugin.getConfig().getString("messages.bossbar", "<light_purple>Typhon")
                .replace("%phase%", String.valueOf(b.phase)));
    }

    /** Aynı ses 4 tick içinde tekrar çalmaz (üst üste binip gürültü olmasın). */
    private void snd(BossState b, Sound sound, float volume, float pitch) {
        Integer last = b.lastSnd.get(sound);
        if (last != null && b.tick - last < 4) {
            return;
        }
        b.lastSnd.put(sound, b.tick);
        b.world.playSound(b.lastLoc, sound, SoundCategory.HOSTILE, volume, pitch);
    }

    /** Katmanlı kükreme: derin gövde sesi + ejderha hırıltısı + yırtıcı tını. */
    private void roar(BossState b, float volume) {
        snd(b, Sound.ENTITY_RAVAGER_ROAR, volume, 0.45f);
        snd(b, Sound.ENTITY_WARDEN_ROAR, volume * 0.7f, 0.6f);
        snd(b, Sound.ENTITY_ENDER_DRAGON_GROWL, volume * 0.6f, 0.5f);
    }

    /** Oyuncu sayısına göre saldırı hasarı çarpanı (üst sınırlı). */
    private double damageScale(BossState b, FileConfiguration cfg, Location at) {
        int near = Math.max(1, playersNear(b.world, at, 45).size());
        double per = cfg.getDouble("boss.damage-per-player-percent", 6.0) / 100.0;
        double cap = cfg.getDouble("boss.damage-scale-cap", 1.5);
        return Math.min(cap, 1.0 + per * (near - 1));
    }

    // ------------------------------------------------------------------ model ve poz

    /** Hedef duruşu hesaplar ve değişen grupların parçalarını günceller. */
    private void updatePose(BossState b, int t, boolean force, double k) {
        Location bl = b.lastLoc;
        b.moving = b.poseLast != null && b.poseLast.getWorld() == bl.getWorld()
                && b.poseLast.distanceSquared(bl) > 0.01;
        b.poseLast = bl.clone();
        double swing = b.moving ? Math.sin(t * 0.3) * 0.55 : 0.0;

        double tgtL = 0.0;
        double tgtR = 0.0;
        double tgtHead = 0.0;
        double tgtJaw = 0.0;
        BossAttack a = b.attack;
        if (b.superState == 1) {
            tgtL = 2.7;
            tgtR = 2.7;
            tgtHead = -0.5;
            tgtJaw = 0.5;
        } else if (b.superState == 2) {
            tgtHead = 0.35;
        } else if (a != null && a.type == 1) {
            tgtL = 2.6;
            tgtR = 2.6;
            tgtHead = -0.2;
        } else if (a != null && a.type == 2) {
            tgtR = 1.6;
            tgtHead = -0.1;
        }
        if (b.roarTicks > 0) {
            tgtHead -= 0.5;
            tgtJaw = Math.max(tgtJaw, 0.6);
            b.roarTicks -= 3;
        }
        if (b.flinch > 0) {
            tgtHead += 0.2;
            b.flinch -= 3;
        }
        if (b.swingFlag) {
            b.swingFlag = false;
            if (b.swingRight) {
                b.raiseR = 1.5;
            } else {
                b.raiseL = 1.5;
            }
            b.swingRight = !b.swingRight;
        }

        // Kanatlar: normalde hafif çırpar, kükrerken ve süperde açılır, yorgunken düşer
        double wingTgt = 0.08 + 0.08 * Math.sin(t * 0.07);
        if (b.superState == 1) {
            wingTgt = 0.75;
        } else if (b.superState == 2) {
            wingTgt = -0.25;
        } else if (b.roarTicks > 0) {
            wingTgt = 0.6;
        } else if (a != null) {
            wingTgt = 0.4;
        }
        b.wingE += (wingTgt - b.wingE) * 0.35;
        b.aWing = b.wingE;
        b.aTail = Math.sin(t * 0.1) * (b.moving ? 0.35 : 0.2) + b.tailSwing;

        b.raiseL += (tgtL - b.raiseL) * 0.45;
        b.raiseR += (tgtR - b.raiseR) * 0.45;
        b.headE += (tgtHead - b.headE) * 0.5;
        b.jawE += (tgtJaw - b.jawE) * 0.5;
        double idle = Math.sin(t * 0.07) * 0.05;
        b.aArmL = -b.raiseL + swing + idle;
        b.aArmR = -b.raiseR - swing - idle;
        b.aLegL = -swing * 0.8;
        b.aLegR = swing * 0.8;
        b.aHead = b.headE;
        b.aJaw = b.jawE;
        b.breath = 0.025 * Math.sin(t * 0.09);
        b.bobL = 0.18 * Math.sin(t * 0.08);
        b.bobR = 0.18 * Math.sin(t * 0.08 + 2.0);

        long[] sig = new long[GROUPS];
        sig[G_BODY] = 0;
        sig[G_ARM_L] = Math.round(b.aArmL * 50);
        sig[G_ARM_R] = Math.round(b.aArmR * 50);
        sig[G_LEG_L] = Math.round(b.aLegL * 50);
        sig[G_LEG_R] = Math.round(b.aLegR * 50);
        sig[G_HEAD] = Math.round(b.aHead * 50);
        sig[G_JAW] = Math.round((b.aHead + b.aJaw) * 50);
        sig[G_CHEST] = Math.round(b.breath * 1000);
        sig[G_CRY_L] = Math.round(b.bobL * 50);
        sig[G_CRY_R] = Math.round(b.bobR * 50);
        sig[G_WING_L] = Math.round(b.aWing * 25);
        sig[G_WING_R] = sig[G_WING_L];
        sig[G_TAIL] = Math.round(b.aTail * 25);

        boolean[] changed = new boolean[GROUPS];
        for (int g = 0; g < GROUPS; g++) {
            changed[g] = force || sig[g] != b.sig[g];
            b.sig[g] = sig[g];
        }


        for (int i = 0; i < b.parts.size(); i++) {
            Part pt = b.parts.get(i);
            if (!changed[pt.group()]) {
                continue;
            }
            Display d = b.displays.get(i);
            if (!d.isValid()) {
                continue;
            }
            Transformation tr = BossPose.transform(pt, b, k);
            d.getScheduler().run(plugin, x -> {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(4);
                d.setTransformation(tr);
            }, null);
        }
    }


    // ------------------------------------------------------------------ döngü

    private void tick(BossState b, ScheduledTask task) {
        if (b.removing) {
            task.cancel();
            return;
        }
        Zombie body = b.body;
        if (!body.isValid()) {
            finishBoss(b, false);
            task.cancel();
            return;
        }
        try {
            b.tick++;
            int t = b.tick;
            Location bl = body.getLocation();
            b.lastLoc = bl;
            FileConfiguration cfg = plugin.getConfig();

            // Kimse yakında değilse model güncellemeleri seyrekleşir (TPS ve paket tasarrufu)
            boolean watched = b.emerging || t - b.lastViewerTick < 100;
            if (b.emerging || (watched ? t % 3 == 0 : t % 20 == 0)) {
                syncParts(b, bl);
            }

            if (b.emerging) {
                emergeTick(b, cfg, t, bl);
                return;
            }

            if (b.spawnShield > 0) {
                b.spawnShield--;
                b.shield = true;
                if (b.spawnShield == 0 && b.superState != 1) {
                    b.shield = false;
                }
            }

            double health = body.getHealth();
            double frac = health / b.maxHealth;
            int ph = frac > 0.66 ? 1 : frac > 0.33 ? 2 : 3;
            if (ph != b.phase) {
                b.phase = ph;
                onPhase(b, cfg, bl);
            }
            if (t % 5 == 0) {
                b.bar.progress((float) Math.max(0.0, Math.min(1.0, frac)));
            }
            if (t % 20 == 0) {
                refreshBar(b);
            }

            if ((watched && t % 3 == 0) || b.poseNow) {
                b.poseNow = false;
                updatePose(b, t, b.forcePose, 1.0);
                b.forcePose = false;
            }

            if (watched || t % 10 == 0) {
                orbitCrystals(b, bl, t);
            }
            healTick(b, cfg, t, health);
            if (watched) {
                ambientTick(b, bl, t);
            }

            boolean busy = superTick(b, cfg, bl, t);
            if (!busy) {
                if (t % 2 == 0) {
                    roamTick(b, cfg, bl);
                }
                if (t % 20 == 0) {
                    stuckTick(b, bl);
                }
                retarget(b, bl, t);
                tailTick(b, cfg, bl);
                attackTick(b, cfg, bl);
            }
        } catch (Throwable e) {
            plugin.logError("Boss döngüsünde hata", e);
            if (++b.errors > 20) {
                finishBoss(b, false);
                task.cancel();
            }
        }
    }

    private void emergeTick(BossState b, FileConfiguration cfg, int t, Location bl) {
        int emergeTicks = Math.max(20, cfg.getInt("boss.emerge-ticks", 80));
        double k = Math.min(1.0, t / (double) emergeTicks);

        if (t % 4 == 0 || t >= emergeTicks) {
            updatePose(b, t, true, Math.max(0.05, k));
        }
        b.world.spawnParticle(Particle.PORTAL, b.home, 40, 2.5, 3.0 * k + 0.5, 2.5, 0.3, null, true);
        b.world.spawnParticle(Particle.END_ROD, b.home.clone().add(0, 3 * k, 0), 6, 1.0, 1.5, 1.0, 0.05, null, true);
        if (t % 20 == 0) {
            snd(b, Sound.ENTITY_ENDER_DRAGON_GROWL, 10.0f, 0.5f + (float) k * 0.4f);
        }
        if (t % 10 == 0) {
            snd(b, Sound.BLOCK_STONE_BREAK, 4.0f, 0.5f);
        }

        if (t >= emergeTicks) {
            b.emerging = false;
            b.lastHitTick = t;
            b.cooldown = 100;
            b.nextAmbient = t + 100;
            b.superTimer = Math.max(20, cfg.getInt("boss.super.first-seconds", 300) * 20);
            b.roarTicks = 30;
            b.poseNow = true;
            b.spawnShield = 100; // doğunca 5 saniye vurulmaz
            b.shield = true;
            b.body.setAI(true);
            b.body.setInvulnerable(false);
            spawnCrystals(b);
            refreshBar(b);
            b.world.spawnParticle(Particle.EXPLOSION_EMITTER, b.home, 6, 2, 1, 2, 0, null, true);
            snd(b, Sound.ENTITY_GENERIC_EXPLODE, 12.0f, 0.5f);
            roar(b, 12.0f);
            plugin.log("Typhon hazır.");
        }
    }

    private void spawnCrystals(BossState b) {
        for (int i = 0; i < 4; i++) {
            Location at = b.lastLoc.clone().add(0, 5.0, 0);
            EnderCrystal c = b.world.spawn(at, EnderCrystal.class, cc -> {
                cc.setShowingBottom(false);
                cc.setPersistent(false);
            });
            b.crystals.add(c);
        }
    }

    /** Parçaları zombinin konumuna taşır. */
    private void syncParts(BossState b, Location bl) {
        // Görünüm zombinin baktığı yöne döner ama öne/arkaya eğilmez (pitch 0)
        Location target = bl.clone();
        target.setPitch(0f);
        target.setYaw(bl.getYaw());
        Location last = b.lastSync;
        if (last != null && last.getWorld() == target.getWorld()
                && last.distanceSquared(target) < 0.0004 && Math.abs(last.getYaw() - target.getYaw()) < 1.0f) {
            return;
        }
        b.lastSync = target.clone();
        for (Display d : b.displays) {
            if (d.isValid()) {
                d.teleportAsync(target);
            }
        }
    }

    private void orbitCrystals(BossState b, Location bl, int t) {
        b.orbit += b.superState == 1 ? 0.18 : 0.05;
        int i = 0;
        for (EnderCrystal c : b.crystals) {
            if (!c.isValid()) {
                continue;
            }
            double a = b.orbit + i * Math.PI / 2;
            Location at = new Location(b.world,
                    bl.getX() + Math.cos(a) * 6.5,
                    bl.getY() + 5.0 + Math.sin(t * 0.1 + i) * 0.4,
                    bl.getZ() + Math.sin(a) * 6.5);
            c.teleportAsync(at);
            if (t % 5 == 0) {
                boolean beam = b.healing || b.superState == 1;
                c.setBeamTarget(beam ? bl.clone().add(0, 3.2, 0) : null);
            }
            i++;
        }
    }

    private void healTick(BossState b, FileConfiguration cfg, int t, double health) {
        int idleTicks = cfg.getInt("boss.heal-idle-seconds", 10) * 20;
        int alive = 0;
        for (EnderCrystal c : b.crystals) {
            if (c.isValid()) {
                alive++;
            }
        }
        boolean can = t - b.lastHitTick > idleTicks && alive > 0 && !b.returning
                && b.healed < b.healCap && health < b.maxHealth;
        b.healing = can;
        if (can && t % 20 == 0) {
            double pct = cfg.getDouble("boss.heal-percent-per-second", 2.0);
            double amount = b.maxHealth * pct / 100.0 * alive / 4.0;
            amount = Math.min(amount, Math.min(b.healCap - b.healed, b.maxHealth - health));
            if (amount > 0) {
                b.body.setHealth(Math.min(b.maxHealth, health + amount));
                b.healed += amount;
                b.world.spawnParticle(Particle.END_ROD, b.lastLoc.clone().add(0, 3, 0), 30, 1.2, 2.0, 1.2, 0.05, null, true);
            }
        }
    }

    /** Kendi sesleri: nefes/hırlama, ayak sesi, ayak dibinde toz. */
    private void ambientTick(BossState b, Location bl, int t) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (t >= b.nextAmbient) {
            b.nextAmbient = t + 120 + rnd.nextInt(100);
            if (b.phase >= 3 && rnd.nextBoolean()) {
                roar(b, 5.0f);
            } else {
                Sound[] idle = {Sound.ENTITY_WARDEN_AMBIENT, Sound.ENTITY_RAVAGER_AMBIENT, Sound.ENTITY_WITHER_AMBIENT};
                snd(b, idle[rnd.nextInt(idle.length)], 4.0f, b.phase >= 2 ? 0.45f : 0.55f);
            }
        }
        if (b.moving && t % 12 == 0) {
            snd(b, Sound.ENTITY_IRON_GOLEM_STEP, 3.0f, 0.5f);
            snd(b, Sound.ENTITY_RAVAGER_STEP, 2.0f, 0.5f);
            b.world.spawnParticle(Particle.LARGE_SMOKE, bl.clone().add(0, 0.2, 0), 6, 0.8, 0.1, 0.8, 0.01, null, true);
        }
        // Ağızdan duman ve ateş, omuzlardan lav damlası
        if (t % 3 == 0) {
            double yawRad = Math.toRadians(bl.getYaw());
            Location mouth = bl.clone().add(-Math.sin(yawRad) * 1.0, 4.9, Math.cos(yawRad) * 1.0);
            b.world.spawnParticle(Particle.LARGE_SMOKE, mouth, 2, 0.2, 0.1, 0.2, 0.02, null, true);
            b.world.spawnParticle(Particle.FLAME, mouth, 2, 0.2, 0.05, 0.2, 0.02, null, true);
        }
        if (t % 10 == 0) {
            b.world.spawnParticle(Particle.DRIPPING_LAVA, bl.clone().add(0, 5.0, 0), 6, 2.0, 0.5, 1.0, 0, null, true);
        }
        if (t % 4 == 0) {
            if (b.phase >= 2) {
                b.world.spawnParticle(Particle.FLAME, bl.clone().add(0, 0.2, 0), 3, 0.9, 0.1, 0.9, 0.01, null, true);
            } else {
                b.world.spawnParticle(Particle.SMALL_FLAME, bl.clone().add(0, 0.2, 0), 4, 0.9, 0.1, 0.9, 0.01, null, true);
            }
        }
    }

    /** Çıktığı çukurun {roam-limit} blok dışına çıkamaz, çıkarsa geri döner. */
    private void roamTick(BossState b, FileConfiguration cfg, Location bl) {
        double limit = cfg.getDouble("boss.roam-limit", 50.0);
        double dist = Math.hypot(bl.getX() - b.home.getX(), bl.getZ() - b.home.getZ());
        if (!b.returning && dist > limit) {
            b.returning = true;
        } else if (b.returning && dist < limit - 10) {
            b.returning = false;
        }
        if (b.returning) {
            b.body.setTarget(null);
            b.body.getPathfinder().moveTo(b.home, 1.3);
        }
        if (dist > limit - 8) {
            double ang = Math.atan2(bl.getZ() - b.home.getZ(), bl.getX() - b.home.getX());
            Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(255, 110, 20), 2.0f);
            for (int i = -6; i <= 6; i++) {
                double a = ang + i * 0.04;
                Location l = new Location(b.world, b.home.getX() + Math.cos(a) * limit, bl.getY() + 0.3,
                        b.home.getZ() + Math.sin(a) * limit);
                b.world.spawnParticle(Particle.DUST, l, 1, 0, 0.5, 0, 0, purple, true);
            }
        }
    }

    /** Bir yere sıkışırsa ya da blok içinde kalırsa kurtarır. */
    private void stuckTick(BossState b, Location bl) {
        if (b.lastPos != null && b.lastPos.getWorld() == bl.getWorld() && b.lastPos.distanceSquared(bl) < 0.25) {
            b.stuck += 20;
        } else {
            b.stuck = 0;
        }
        b.lastPos = bl.clone();

        boolean inBlock = bl.getBlock().getType().isSolid() || bl.clone().add(0, 1, 0).getBlock().getType().isSolid();
        if (inBlock) {
            int y = b.world.getHighestBlockYAt(bl.getBlockX(), bl.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            b.body.teleportAsync(new Location(b.world, bl.getX(), y, bl.getZ(), bl.getYaw(), bl.getPitch()));
            b.stuck = 0;
            return;
        }

        if (b.stuck >= 100 && !b.returning) {
            Player near = nearestPlayer(b.world, bl, 40);
            if (near != null && near.getLocation().distance(bl) > 6) {
                Location p = near.getLocation();
                double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                Location to = new Location(b.world, p.getX() + Math.cos(a) * 3, p.getY(), p.getZ() + Math.sin(a) * 3);
                b.world.spawnParticle(Particle.PORTAL, bl.clone().add(0, 2, 0), 80, 1.5, 3, 1.5, 0.5, null, true);
                b.body.teleportAsync(to);
                b.stuck = 0;
            }
        }
    }

    // ------------------------------------------------------------------ süper saldırı

    private double blastRadius(BossState b, FileConfiguration cfg) {
        return b.phase >= 3 ? cfg.getDouble("boss.super.radius-phase3", 40.0)
                : cfg.getDouble("boss.super.radius", 30.0);
    }

    /** true dönerse süper hazırlığı sürüyor, normal davranışlar durur. */
    private boolean superTick(BossState b, FileConfiguration cfg, Location bl, int t) {
        if (b.superState == 1) {
            chargeTick(b, cfg, bl, t);
            return true;
        }
        if (b.superState == 2) {
            if (--b.exhaustLeft <= 0) {
                b.superState = 0;
                setBase(b.body, Attribute.MOVEMENT_SPEED, b.baseSpeed * (1.0 + 0.15 * (b.phase - 1)));
                b.poseNow = true;
            } else if (t % 10 == 0) {
                b.world.spawnParticle(Particle.LARGE_SMOKE, bl.clone().add(0, 3, 0), 12, 1.2, 2.0, 1.2, 0.02, null, true);
            }
            return false;
        }
        if (b.superTimer > 0) {
            b.superTimer--;
            return false;
        }
        if (b.attack != null) {
            return false;
        }
        startSuper(b, cfg, bl);
        return true;
    }

    private void startSuper(BossState b, FileConfiguration cfg, Location bl) {
        b.superState = 1;
        b.superTotal = Math.max(20, cfg.getInt("boss.super.charge-seconds", 5) * 20);
        b.superLeft = b.superTotal;
        b.shield = true;
        b.attack = null;
        b.superCount++;
        b.body.setTarget(null);
        b.body.setAI(false);
        b.poseNow = true;
        roar(b, 14.0f);
        warnPlayers(b, cfg);
        plugin.log("Typhon süper saldırıya hazırlanıyor.");
    }

    private void chargeTick(BossState b, FileConfiguration cfg, Location bl, int t) {
        b.superLeft--;
        double progress = 1.0 - b.superLeft / (double) b.superTotal;
        double radius = blastRadius(b, cfg);

        if (t % 2 == 0) {
            shieldBubble(b, bl);
        }
        if (t % 3 == 0) {
            drawRing(b.world, bl, Math.max(2.0, radius * progress), progress);
        }
        b.world.spawnParticle(Particle.PORTAL, bl.clone().add(0, 3, 0), 40, 3, 3, 3, 0.8, null, true);
        if (b.superLeft % 20 == 0 && b.superLeft > 0) {
            snd(b, Sound.ENTITY_ENDER_DRAGON_GROWL, 10.0f, 0.5f + 0.4f * (float) progress);
            warnPlayers(b, cfg);
        }
        if (b.superLeft <= 0) {
            blast(b, cfg, bl);
        }
    }

    private void blast(BossState b, FileConfiguration cfg, Location bl) {
        double radius = blastRadius(b, cfg);
        double maxHit = cfg.getDouble("boss.max-hit", 10.0);
        double damage = Math.min(cfg.getDouble("boss.super.damage", 6.0) * damageScale(b, cfg, bl), maxHit);
        double power = cfg.getDouble("boss.super.knockback", 2.2);
        World world = b.world;
        Location c = bl.clone();

        world.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 20, 4, 2, 4, 0, null, true);
        world.spawnParticle(Particle.FLAME, c.clone().add(0, 1, 0), 800, 8, 3, 8, 0.4, null, true);
        world.spawnParticle(Particle.END_ROD, c.clone().add(0, 2, 0), 300, 8, 4, 8, 0.5, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, 1, 0), 400, 8, 3, 8, 0.2, null, true);
        for (double r = 3.0; r <= radius; r += 3.0) {
            drawRing(world, c, r, 1.0);
        }
        snd(b, Sound.ENTITY_GENERIC_EXPLODE, 16.0f, 0.5f);
        snd(b, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 16.0f, 0.5f);
        roar(b, 14.0f);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, tt -> {
                if (!p.getWorld().equals(world)) {
                    return;
                }
                Location pl = p.getLocation();
                double dist = pl.distance(c);
                if (dist > radius) {
                    return;
                }
                Vector away = pl.toVector().subtract(c.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) {
                    away = new Vector(1, 0, 0);
                }
                double factor = Math.max(0.0, 1.0 - dist / radius);
                p.damage(damage);
                p.setVelocity(away.normalize().multiply(power * (0.6 + factor)).setY(0.9 + 0.5 * factor));
            }, null);
        }

        // Kalkan iner, Typhon yorulur
        b.shield = false;
        b.superState = 2;
        b.exhaustLeft = Math.max(20, cfg.getInt("boss.super.exhausted-seconds", 5) * 20);
        b.superTimer = Math.max(20, cfg.getInt("boss.super.interval-seconds", 300) * 20);
        b.cooldown = b.exhaustLeft + 40;
        b.roarTicks = 30;
        b.poseNow = true;
        b.body.setAI(true);
        setBase(b.body, Attribute.MOVEMENT_SPEED, b.baseSpeed * 0.4);
        plugin.log("Typhon süper saldırıyı yaptı (" + b.superCount + ").");
    }

    private void shieldBubble(BossState b, Location bl) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 110, 30), 1.5f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 36; i++) {
            double u = rnd.nextDouble() * 2 - 1;
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(1 - u * u);
            Location l = new Location(b.world,
                    bl.getX() + r * Math.cos(a) * 3.0,
                    bl.getY() + 3.0 + u * 3.4,
                    bl.getZ() + r * Math.sin(a) * 3.0);
            b.world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dust, true);
        }
    }

    private void warnPlayers(BossState b, FileConfiguration cfg) {
        Component msg = mm.deserialize(cfg.getString("messages.super-warning",
                "<dark_purple>⚠ <light_purple><bold>ʙᴏşʟᴜᴋ ᴘᴀᴛʟᴀᴍᴀsɪ</bold> <gray>geliyor! <white>Uzaklaş!"));
        double r = cfg.getDouble("boss.super.warning-radius", 70.0);
        Location at = b.lastLoc;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (p.getWorld().equals(b.world) && p.getLocation().distanceSquared(at) <= r * r) {
                    p.sendActionBar(msg);
                }
            }, null);
        }
    }

    // ------------------------------------------------------------------ saldırılar

    private void attackTick(BossState b, FileConfiguration cfg, Location bl) {
        if (b.attack != null) {
            runAttack(b, cfg);
            return;
        }
        if (--b.cooldown > 0) {
            return;
        }
        List<Player> near = playersNear(b.world, bl, 16);
        if (near.isEmpty()) {
            b.cooldown = 20;
            return;
        }
        double slamDamage = cfg.getDouble("boss.slam-damage", 6.0);
        double strikeDamage = cfg.getDouble("boss.strike-damage", 5.0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (b.phase >= 2 && rnd.nextBoolean()) {
            int count = b.phase >= 3 ? 5 : 3;
            List<Location> centers = new ArrayList<>();
            List<Player> pool = new ArrayList<>(playersNear(b.world, bl, 30));
            for (int i = 0; i < count && !pool.isEmpty(); i++) {
                Player p = pool.remove(rnd.nextInt(pool.size()));
                centers.add(p.getLocation().clone());
            }
            if (!centers.isEmpty()) {
                b.attack = new BossAttack(2, b.phase >= 3 ? 30 : 36, centers, 3.0, strikeDamage);
                b.poseNow = true;
                snd(b, Sound.ENTITY_ENDER_DRAGON_GROWL, 8.0f, 1.2f);
                return;
            }
        }
        double radius = b.phase >= 3 ? 9.0 : 7.0;
        b.attack = new BossAttack(1, b.phase >= 3 ? 24 : 30, List.of(bl.clone()), radius, slamDamage);
        b.poseNow = true;
        roar(b, 9.0f);
    }

    private void runAttack(BossState b, FileConfiguration cfg) {
        BossAttack a = b.attack;
        a.left--;
        double progress = 1.0 - a.left / (double) a.total;
        if (a.left % 2 == 0) {
            for (Location c : a.centers) {
                drawRing(b.world, c, a.radius, progress);
            }
        }
        if (a.left > 0) {
            return;
        }

        double maxHit = cfg.getDouble("boss.max-hit", 10.0);
        for (Location c : a.centers) {
            b.world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, a.radius / 3, 0.5, a.radius / 3, 0, null, true);
            b.world.spawnParticle(Particle.FLAME, c, 60, a.radius / 2, 0.3, a.radius / 2, 0.1, null, true);
            b.world.spawnParticle(Particle.END_ROD, c.clone().add(0, 1, 0), 40, a.radius / 2, 1.0, a.radius / 2, 0.1, null, true);
            b.world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 6.0f, 0.7f);
            hurt(b.world, c, a.radius, Math.min(a.damage * damageScale(b, cfg, c), maxHit));
            b.world.playSound(c, Sound.ITEM_FIRECHARGE_USE, SoundCategory.HOSTILE, 4.0f, 0.6f);
        }
        b.attack = null;
        b.raiseL = 0.0; // kollar hızla iner
        b.raiseR = 0.0;
        b.poseNow = true;
        int seconds = Math.max(2, cfg.getInt("boss.attack-cooldown-seconds", 8));
        double factor = b.phase == 1 ? 1.0 : b.phase == 2 ? 0.75 : 0.5;
        b.cooldown = (int) (seconds * 20 * factor);
    }

    private void drawRing(World w, Location c, double r, double progress) {
        Particle.DustOptions dust = new Particle.DustOptions(
                Color.fromRGB(255, (int) (190 - 170 * progress), 10), 2.0f);
        int pts = Math.max(24, (int) (r * 8));
        for (int i = 0; i < pts; i++) {
            double a = i * Math.PI * 2 / pts;
            Location l = new Location(w, c.getX() + Math.cos(a) * r, c.getY() + 0.2, c.getZ() + Math.sin(a) * r);
            w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dust, true);
        }
    }

    /** Alandaki oyunculara hasar ve savrulma (her oyuncu kendi thread'inde). */
    private void hurt(World world, Location c, double radius, double damage) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (!p.getWorld().equals(world)) {
                    return;
                }
                Location pl = p.getLocation();
                double horizontal = Math.hypot(pl.getX() - c.getX(), pl.getZ() - c.getZ());
                if (horizontal > radius || pl.getY() - c.getY() > 3.5) {
                    return;
                }
                p.damage(damage);
                Vector away = new Vector(pl.getX() - c.getX(), 0, pl.getZ() - c.getZ());
                if (away.lengthSquared() < 0.01) {
                    away = new Vector(1, 0, 0);
                }
                p.setVelocity(away.normalize().multiply(0.9).setY(0.6));
            }, null);
        }
    }

    // ------------------------------------------------------------------ evre ve bar

    private void onPhase(BossState b, FileConfiguration cfg, Location bl) {
        if (b.phase >= 2) {
            Material red = Material.REDSTONE_BLOCK;
            for (int i = 0; i < b.parts.size(); i++) {
                Part pt = b.parts.get(i);
                BlockDisplay d = (BlockDisplay) b.displays.get(i);
                if (pt.kind() == 1 || pt.kind() == 2) {
                    d.getScheduler().run(plugin, x -> d.setBlock(red.createBlockData()), null);
                } else if (pt.kind() == 3) {
                    d.getScheduler().run(plugin, x -> {
                        d.setGlowing(true);
                        d.setGlowColorOverride(Color.RED);
                    }, null);
                }
            }
        }
        b.forcePose = true; // çatlaklar görünür olsun
        b.poseNow = true;
        b.roarTicks = 30;
        if (b.superState != 2) {
            setBase(b.body, Attribute.MOVEMENT_SPEED, b.baseSpeed * (1.0 + 0.15 * (b.phase - 1)));
        }
        if (b.phase == 3 && b.superCount == 0 && cfg.getBoolean("boss.super.force-on-phase3", true)) {
            b.superTimer = Math.min(b.superTimer, 100);
        }
        b.bar.color(b.phase >= 3 ? BossBar.Color.PURPLE : BossBar.Color.RED);
        b.bar.name(barTitle(b));
        b.world.spawnParticle(Particle.EXPLOSION_EMITTER, bl.clone().add(0, 3, 0), 4, 2, 2, 2, 0, null, true);
        b.world.spawnParticle(Particle.LARGE_SMOKE, bl.clone().add(0, 3, 0), 120, 2, 3, 2, 0.1, null, true);
        snd(b, Sound.ENTITY_ENDER_DRAGON_GROWL, 12.0f, 0.6f);
        roar(b, 12.0f);
        plugin.log("Typhon evre " + b.phase);
    }

    private void refreshBar(BossState b) {
        double r = plugin.getConfig().getDouble("boss.bar-radius", 150.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (p.getWorld().equals(b.world) && p.getLocation().distanceSquared(b.lastLoc) <= r * r) {
                    b.lastViewerTick = b.tick;
                    p.showBossBar(b.bar);
                } else {
                    p.hideBossBar(b.bar);
                }
            }, null);
        }
    }

    private void hideBar(BossState b) {
        if (b.bar == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> p.hideBossBar(b.bar), null);
        }
    }

    // ------------------------------------------------------------------ bitiş

    /** /meteor etkinlik -k : boss'u hemen kaldırır. */
    public boolean removeNow() {
        BossState b = boss;
        if (b == null) {
            return false;
        }
        finishBoss(b, false);
        return true;
    }

    public void onDeath(EntityDeathEvent e) {
        BossState b = boss;
        if (b == null || !e.getEntity().getUniqueId().equals(b.bodyId)) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        Location deathLoc = e.getEntity().getLocation();
        World world = deathLoc.getWorld();

        // Öldüren oyuncu: son vuruşu yapan, yoksa en çok hasar veren
        Player killer = e.getEntity().getKiller();
        if (killer == null) {
            UUID top = null;
            double best = 0.0;
            for (Map.Entry<UUID, Double> en : b.damage.entrySet()) {
                if (en.getValue() > best) {
                    best = en.getValue();
                    top = en.getKey();
                }
            }
            if (top != null) {
                killer = Bukkit.getPlayer(top);
            }
        }

        // Ödüller
        List<ItemStack> items = new ArrayList<>();
        for (String reward : cfg.getStringList("boss.rewards")) {
            String[] parts = reward.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            if (m == null || m.isAir()) {
                plugin.log("Bilinmeyen ödül eşyası: " + reward);
                continue;
            }
            while (amount > 0) {
                int n = Math.min(amount, m.getMaxStackSize());
                items.add(new ItemStack(m, n));
                amount -= n;
            }
        }
        int exp = cfg.getInt("boss.exp", 300);
        e.getDrops().clear();
        if (killer != null) {
            // Eşyalar ve tecrübe doğrudan öldüren oyuncunun envanterine gider
            e.setDroppedExp(0);
            rewards.give(killer, items, exp, deathLoc);
            rewards.announce(killer.getName(), world, deathLoc, cfg);
        } else {
            e.getDrops().addAll(items);
            e.setDroppedExp(exp);
        }
        plugin.log("Typhon öldü" + (killer != null ? " (" + killer.getName() + ")" : "") + ".");
        finishBoss(b, true);
        plugin.getEvents().bossDied();
    }

    private void finishBoss(BossState b, boolean died) {
        synchronized (b) {
            if (b.removing) {
                return;
            }
            b.removing = true;
        }
        b.shield = false;
        if (boss == b) {
            boss = null;
        }
        hideBar(b);

        Location at = b.lastLoc;
        World world = b.world;
        Bukkit.getRegionScheduler().run(plugin, at, t -> {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, at.clone().add(0, 3, 0), 8, 2, 2, 2, 0, null, true);
            world.spawnParticle(Particle.END_ROD, at.clone().add(0, 3, 0), 150, 2, 3, 2, 0.4, null, true);
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 3, 0), 150, 2, 3, 2, 0.1, null, true);
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 12.0f, 0.5f);
            if (died) {
                world.playSound(at, Sound.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 12.0f, 0.6f);
                world.playSound(at, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, 10.0f, 0.7f);
            }
        });

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Display d : b.displays) {
            Transformation scatter = new Transformation(
                    new Vector3f((float) rnd.nextDouble(-4, 4), (float) rnd.nextDouble(1, 5), (float) rnd.nextDouble(-4, 4)),
                    new Quaternionf().rotateXYZ((float) rnd.nextDouble(-3, 3), (float) rnd.nextDouble(-3, 3), (float) rnd.nextDouble(-3, 3)),
                    new Vector3f(0.01f, 0.01f, 0.01f),
                    new Quaternionf());
            d.getScheduler().run(plugin, t -> {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(20);
                d.setTransformation(scatter);
            }, null);
            d.getScheduler().runDelayed(plugin, t -> d.remove(), null, 26L);
        }
        for (EnderCrystal c : b.crystals) {
            c.getScheduler().run(plugin, t -> c.remove(), null);
        }
        if (!died && b.body != null) {
            Zombie z = b.body;
            z.getScheduler().run(plugin, t -> z.remove(), null);
        }
    }


    // ------------------------------------------------------------------ hedef değiştirme, kuyruk

    /** Zombi tek oyuncuya kilitlenmesin: belirli aralıkla hedefi başka oyuncuya çevirir. */
    private void retarget(BossState b, Location bl, int t) {
        if (t < b.nextRetarget) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        b.nextRetarget = t + 80 + rnd.nextInt(80);
        if (b.returning) {
            return;
        }
        List<Player> near = playersNear(b.world, bl, 45);
        if (near.size() < 2) {
            return;
        }
        LivingEntity cur = b.body.getTarget();
        List<Player> pool = new ArrayList<>();
        for (Player p : near) {
            if (p.isValid() && (cur == null || !p.getUniqueId().equals(cur.getUniqueId()))) {
                pool.add(p);
            }
        }
        if (!pool.isEmpty()) {
            b.body.setTarget(pool.get(rnd.nextInt(pool.size())));
        }
    }

    /** Arkasına geçen oyuncuya kuyruk darbesi (hazırlık: kuyruk kıvrılır, sonra savurur). */
    private void tailTick(BossState b, FileConfiguration cfg, Location bl) {
        double yaw = Math.toRadians(bl.getYaw());
        if (b.tailLeft > 0) {
            b.tailLeft--;
            double prog = 1.0 - b.tailLeft / 16.0;
            b.tailSwing = prog < 0.6 ? 0.9 * (prog / 0.6) : 0.9 - 1.8 * ((prog - 0.6) / 0.4);
            b.poseNow = true;
            if (b.tailLeft % 2 == 0) {
                drawTailArc(b, bl, yaw, 0.3 + 0.7 * prog);
            }
            if (b.tailLeft == 0) {
                tailStrike(b, cfg, bl, yaw);
                b.tailSwing = 0.0;
                b.tailCd = Math.max(20, cfg.getInt("boss.tail-cooldown-seconds", 5) * 20);
            }
            return;
        }
        if (--b.tailCd > 0 || b.attack != null || b.superState != 0) {
            return;
        }
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        for (Player p : playersNear(b.world, bl, 9)) {
            Location pl = p.getLocation();
            double dx = pl.getX() - bl.getX();
            double dz = pl.getZ() - bl.getZ();
            double len = Math.hypot(dx, dz);
            if (len < 0.1) {
                continue;
            }
            if ((dx * fx + dz * fz) / len < -0.2) { // arkasında
                b.tailLeft = 16;
                snd(b, Sound.ENTITY_RAVAGER_ROAR, 6.0f, 0.8f);
                return;
            }
        }
        b.tailCd = 10;
    }

    private void drawTailArc(BossState b, Location bl, double yaw, double strength) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, (int) (160 - 120 * strength), 10), 1.8f);
        for (int i = -6; i <= 6; i++) {
            double a = yaw + Math.PI + i * 0.25;
            for (double d = 3.0; d <= 9.0; d += 2.0) {
                Location l = new Location(b.world, bl.getX() - Math.sin(a) * d, bl.getY() + 0.3, bl.getZ() + Math.cos(a) * d);
                b.world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dust, true);
            }
        }
    }

    private void tailStrike(BossState b, FileConfiguration cfg, Location bl, double yaw) {
        double dmg = Math.min(cfg.getDouble("boss.tail-damage", 4.0) * damageScale(b, cfg, bl),
                cfg.getDouble("boss.max-hit", 10.0));
        World world = b.world;
        Location c = bl.clone();
        for (int i = -6; i <= 6; i++) {
            double a = yaw + Math.PI + i * 0.25;
            for (double d = 2.0; d <= 9.0; d += 1.5) {
                Location l = new Location(world, c.getX() - Math.sin(a) * d, c.getY() + 0.5, c.getZ() + Math.cos(a) * d);
                world.spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0, null, true);
                world.spawnParticle(Particle.FLAME, l, 2, 0.2, 0.2, 0.2, 0.02, null, true);
            }
        }
        snd(b, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 8.0f, 0.5f);
        snd(b, Sound.ENTITY_RAVAGER_ATTACK, 8.0f, 0.5f);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (!p.getWorld().equals(world)) {
                    return;
                }
                Location pl = p.getLocation();
                double dx = pl.getX() - c.getX();
                double dz = pl.getZ() - c.getZ();
                double len = Math.hypot(dx, dz);
                if (len > 9.0 || len < 0.1 || Math.abs(pl.getY() - c.getY()) > 4.0) {
                    return;
                }
                if ((dx * fx + dz * fz) / len > 0.3) { // sadece arka yarım daire
                    return;
                }
                p.damage(dmg);
                p.setVelocity(new Vector(dx / len, 0, dz / len).multiply(1.1).setY(0.5));
            }, null);
        }
    }

    // ------------------------------------------------------------------ olaylar (BossListener çağırır)

    public boolean isBody(Entity e) {
        BossState b = boss;
        return b != null && e.getUniqueId().equals(b.bodyId);
    }

    public boolean isCrystal(Entity e) {
        BossState b = boss;
        if (b == null) {
            return false;
        }
        for (EnderCrystal c : b.crystals) {
            if (c.getUniqueId().equals(e.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public Location homeOrNull() {
        BossState b = boss;
        return b == null ? null : b.home;
    }

    /** Boss ve kristallere gelen/giden hasar kuralları. */
    public void onDamage(EntityDamageEvent e) {
        BossState b = boss;
        if (b == null) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        Entity victim = e.getEntity();

        // Boss'un verdiği hasara üst sınır (tek vuruşta ölüm olmasın)
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager().getUniqueId().equals(b.bodyId)) {
            e.setDamage(Math.min(e.getDamage(), cfg.getDouble("boss.max-hit", 10.0)));
            b.swingFlag = true;
            b.poseNow = true;
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_RAVAGER_ATTACK,
                    SoundCategory.HOSTILE, 2.0f, 0.6f);
            return;
        }

        if (victim.getUniqueId().equals(b.bodyId)) {
            if (b.emerging || b.removing) {
                e.setCancelled(true);
                return;
            }
            // Süper sırasında kimse vuramaz
            if (b.shield) {
                e.setCancelled(true);
                if (b.tick - b.lastDing >= 4) {
                    b.lastDing = b.tick;
                    Location at = b.lastLoc.clone().add(0, 3, 0);
                    b.world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.HOSTILE, 2.0f, 1.3f);
                    b.world.spawnParticle(Particle.END_ROD, at, 8, 1.5, 1.5, 1.5, 0.05, null, true);
                }
                return;
            }
            DamageCause cause = e.getCause();
            if (IMMUNE.contains(cause)) {
                e.setCancelled(true);
            } else if (cause == DamageCause.VOID) {
                e.setCancelled(true);
                b.body.teleportAsync(b.home);
            } else if (TRAP.contains(cause)) {
                e.setDamage(Math.min(e.getDamage(), cfg.getDouble("boss.trap-damage-cap", 2.0)));
            } else if (b.superState == 2 && attackerOf(e) != null) {
                // Süperden sonra yorgun: ekstra hasar alır
                double bonus = cfg.getDouble("boss.super.exhausted-bonus-percent", 25.0);
                e.setDamage(e.getDamage() * (1.0 + bonus / 100.0));
            }
            return;
        }

        if (isCrystal(victim)) {
            e.setCancelled(true); // kristal patlamasın, biz kıralım
            if (attackerOf(e) != null) {
                breakCrystal(b, (EnderCrystal) victim);
            }
        }
    }

    /** Hasarı kaydeder, acı sesi ve sarsılma animasyonunu oynatır. */
    public void onDamageDone(EntityDamageEvent e) {
        BossState b = boss;
        if (b == null || !e.getEntity().getUniqueId().equals(b.bodyId)) {
            return;
        }
        Player attacker = attackerOf(e);
        if (attacker != null) {
            b.damage.merge(attacker.getUniqueId(), e.getFinalDamage(), Double::sum);
            b.lastHitTick = b.tick;
        }
        b.flinch = 4;
        b.poseNow = true;
        if (b.tick - b.lastHurt >= 6) {
            b.lastHurt = b.tick;
            snd(b, Sound.ENTITY_RAVAGER_HURT, 3.0f, 0.55f);
            snd(b, Sound.BLOCK_STONE_BREAK, 2.0f, 0.7f);
        }
    }

    private Player attackerOf(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent by)) {
            return null;
        }
        Entity d = by.getDamager();
        if (d instanceof Player p) {
            return p;
        }
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    private void breakCrystal(BossState b, EnderCrystal c) {
        if (!b.crystals.remove(c)) {
            return;
        }
        Location l = c.getLocation();
        b.world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 1, 0, 0, 0, 0, null, true);
        b.world.spawnParticle(Particle.END_ROD, l, 40, 0.5, 0.5, 0.5, 0.2, null, true);
        b.world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4.0f, 1.4f);
        c.getScheduler().run(plugin, t -> c.remove(), null);
    }

    // ------------------------------------------------------------------ yardımcı

    private List<Player> playersNear(World world, Location loc, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(world) && p.getLocation().distanceSquared(loc) <= radius * radius) {
                out.add(p);
            }
        }
        return out;
    }

    private Player nearestPlayer(World world, Location loc, double radius) {
        Player best = null;
        double bestD = radius * radius;
        for (Player p : playersNear(world, loc, radius)) {
            double d = p.getLocation().distanceSquared(loc);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
