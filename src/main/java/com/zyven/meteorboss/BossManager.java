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

    /** Görünüm parçası. Ölçüler metre, ayak ortasına göre; kind: 0 normal, 1 damar, 2 göz. */
    private record Part(Material mat, double cx, double cy, double cz,
                        double sx, double sy, double sz, int kind) {}

    private static final List<Part> PARTS = List.of(
            new Part(Material.OBSIDIAN, -0.55, 0.0, 0.0, 0.95, 2.3, 0.95, 0),        // sol bacak
            new Part(Material.OBSIDIAN, 0.55, 0.0, 0.0, 0.95, 2.3, 0.95, 0),         // sağ bacak
            new Part(Material.CRYING_OBSIDIAN, 0.0, 2.2, 0.0, 2.5, 2.4, 1.3, 0),     // gövde
            new Part(Material.OBSIDIAN, 0.0, 4.0, 0.0, 3.9, 0.7, 1.5, 0),            // omuzlar
            new Part(Material.OBSIDIAN, -2.15, 0.7, 0.0, 0.9, 3.9, 0.9, 0),          // sol kol
            new Part(Material.OBSIDIAN, 2.15, 0.7, 0.0, 0.9, 3.9, 0.9, 0),           // sağ kol
            new Part(Material.END_STONE_BRICKS, -2.15, 0.2, 0.15, 1.1, 0.7, 1.1, 0), // sol pençe
            new Part(Material.END_STONE_BRICKS, 2.15, 0.2, 0.15, 1.1, 0.7, 1.1, 0), // sağ pençe
            new Part(Material.END_STONE, 0.0, 4.7, 0.0, 1.6, 1.5, 1.5, 0),           // kafatası
            new Part(Material.END_STONE_BRICKS, -0.6, 6.2, 0.0, 0.3, 1.0, 0.3, 0),   // sol boynuz
            new Part(Material.END_STONE_BRICKS, 0.6, 6.2, 0.0, 0.3, 1.0, 0.3, 0),    // sağ boynuz
            new Part(Material.AMETHYST_BLOCK, -0.4, 5.35, 0.76, 0.4, 0.25, 0.1, 2),  // sol göz
            new Part(Material.AMETHYST_BLOCK, 0.4, 5.35, 0.76, 0.4, 0.25, 0.1, 2),   // sağ göz
            new Part(Material.AMETHYST_BLOCK, -0.6, 2.4, 0.67, 0.12, 1.9, 0.06, 1),  // damarlar
            new Part(Material.AMETHYST_BLOCK, 0.0, 2.4, 0.67, 0.12, 1.9, 0.06, 1),
            new Part(Material.AMETHYST_BLOCK, 0.6, 2.4, 0.67, 0.12, 1.9, 0.06, 1)
    );

    private static final Set<DamageCause> IMMUNE = EnumSet.of(
            DamageCause.SUFFOCATION, DamageCause.DROWNING, DamageCause.FALL, DamageCause.CRAMMING,
            DamageCause.FLY_INTO_WALL, DamageCause.FREEZE, DamageCause.DRYOUT);

    private static final Set<DamageCause> TRAP = EnumSet.of(
            DamageCause.LAVA, DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.HOT_FLOOR,
            DamageCause.CONTACT, DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION,
            DamageCause.MAGIC, DamageCause.POISON, DamageCause.WITHER, DamageCause.LIGHTNING,
            DamageCause.DRAGON_BREATH, DamageCause.FALLING_BLOCK);

    /** Telegraflı (önceden uyarı veren) saldırı. */
    private static final class Attack {
        final int type; // 1 = yer vuruşu, 2 = boşluk darbesi
        final int total;
        int left;
        final List<Location> centers;
        final double radius;
        final double damage;

        Attack(int type, int total, List<Location> centers, double radius, double damage) {
            this.type = type;
            this.total = total;
            this.left = total;
            this.centers = centers;
            this.radius = radius;
            this.damage = damage;
        }
    }

    private static final class Boss {
        final Session session;
        final World world;
        final Location home;
        Zombie body;
        UUID bodyId;
        BossBar bar;
        final List<BlockDisplay> displays = new ArrayList<>();
        final List<EnderCrystal> crystals = new CopyOnWriteArrayList<>();
        final Map<UUID, Double> damage = new ConcurrentHashMap<>();
        volatile boolean emerging = true;
        volatile boolean removing = false;
        volatile boolean healing = false;
        volatile int phase = 1;
        volatile int tick = 0;
        volatile int lastHitTick = 0;
        volatile Location lastLoc;
        double maxHealth;
        double healed;
        double healCap;
        double baseSpeed;
        Attack attack;
        int cooldown = 100;
        boolean returning;
        Location lastPos;
        int stuck;
        int errors;
        Location lastSync;

        Boss(Session session, World world, Location home) {
            this.session = session;
            this.world = world;
            this.home = home;
            this.lastLoc = home;
        }
    }

    private final MeteorBossPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private volatile Boss boss;

    public BossManager(MeteorBossPlugin plugin) {
        this.plugin = plugin;
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
        Boss b = null;
        try {
            if (boss != null) {
                return;
            }
            FileConfiguration cfg = plugin.getConfig();
            int altarY = ArenaPlanner.altarY(s.groundY, s.radius, world.getMinHeight());
            Location home = new Location(world, s.centerX + 0.5, altarY + 1, s.centerZ + 0.5);
            Boss nb = new Boss(s, world, home);
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

            for (Part p : PARTS) {
                BlockDisplay d = world.spawn(home, BlockDisplay.class, dd -> {
                    dd.setBlock(p.mat().createBlockData());
                    dd.setTransformation(transform(p, 0.05));
                    dd.setPersistent(false);
                    dd.setInterpolationDuration(4);
                    dd.setTeleportDuration(3);
                    dd.setViewRange(3.0f);
                    if (p.kind() != 0) {
                        dd.setBrightness(new Display.Brightness(15, 15));
                    }
                });
                nb.displays.add(d);
            }

            nb.bar = BossBar.bossBar(barTitle(nb), 1.0f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
            boss = nb;
            plugin.log("Typhon çıkıyor (can: " + (int) nb.maxHealth + ").");

            world.playSound(home, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 12.0f, 0.5f);
            z.getScheduler().runAtFixedRate(plugin, task -> tick(nb, task), () -> finishBoss(nb, false), 1L, 1L);
        } catch (Throwable e) {
            plugin.logError("Boss çıkarılamadı", e);
            if (b != null) {
                finishBoss(b, false);
            }
        }
    }

    private void setup(Zombie z, Boss b, FileConfiguration cfg) {
        setBase(z, Attribute.MAX_HEALTH, b.maxHealth);
        z.setHealth(b.maxHealth);
        setBase(z, Attribute.ATTACK_DAMAGE, cfg.getDouble("boss.melee-damage", 8.0));
        setBase(z, Attribute.MOVEMENT_SPEED, b.baseSpeed);
        setBase(z, Attribute.FOLLOW_RANGE, 60.0);
        setBase(z, Attribute.KNOCKBACK_RESISTANCE, 0.8);
        setBase(z, Attribute.SCALE, 3.0);
        z.setBaby(false);
        z.setCanBreakDoors(false);
        z.setShouldBurnInDay(false);
        z.setRemoveWhenFarAway(false);
        z.setPersistent(false);
        z.setCanPickupItems(false);
        z.setInvisible(true);
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

    private static Transformation transform(Part p, double k) {
        return new Transformation(
                new Vector3f((float) ((p.cx() - p.sx() / 2) * k), (float) (p.cy() * k), (float) ((p.cz() - p.sz() / 2) * k)),
                new Quaternionf(),
                new Vector3f((float) (p.sx() * k), (float) (p.sy() * k), (float) (p.sz() * k)),
                new Quaternionf());
    }

    private Component barTitle(Boss b) {
        return mm.deserialize(plugin.getConfig().getString("messages.bossbar", "<light_purple>Typhon")
                .replace("%phase%", String.valueOf(b.phase)));
    }

    // ------------------------------------------------------------------ döngü

    private void tick(Boss b, ScheduledTask task) {
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

            syncParts(b, bl);

            if (b.emerging) {
                emergeTick(b, cfg, t, bl);
                return;
            }

            double health = body.getHealth();
            double frac = health / b.maxHealth;
            int ph = frac > 0.66 ? 1 : frac > 0.33 ? 2 : 3;
            if (ph != b.phase) {
                b.phase = ph;
                onPhase(b, bl);
            }
            if (t % 5 == 0) {
                b.bar.progress((float) Math.max(0.0, Math.min(1.0, frac)));
            }
            if (t % 20 == 0) {
                refreshBar(b);
            }

            orbitCrystals(b, bl, t);
            healTick(b, cfg, t, health);
            if (t % 2 == 0) {
                roamTick(b, cfg, bl);
            }
            if (t % 20 == 0) {
                stuckTick(b, bl);
            }
            attackTick(b, cfg, bl);

            if (t % 6 == 0) {
                b.world.spawnParticle(Particle.PORTAL, bl.clone().add(0, 0.3, 0), 6, 0.8, 0.1, 0.8, 0.05, null, true);
            }
        } catch (Throwable e) {
            plugin.logError("Boss döngüsünde hata", e);
            if (++b.errors > 20) {
                finishBoss(b, false);
                task.cancel();
            }
        }
    }

    private void emergeTick(Boss b, FileConfiguration cfg, int t, Location bl) {
        int emergeTicks = Math.max(20, cfg.getInt("boss.emerge-ticks", 80));
        double k = Math.min(1.0, t / (double) emergeTicks);

        if (t % 4 == 0 || t >= emergeTicks) {
            for (int i = 0; i < PARTS.size(); i++) {
                BlockDisplay d = b.displays.get(i);
                Transformation tr = transform(PARTS.get(i), Math.max(0.05, k));
                d.getScheduler().run(plugin, x -> d.setTransformation(tr), null);
            }
        }
        b.world.spawnParticle(Particle.PORTAL, b.home, 40, 2.5, 3.0 * k + 0.5, 2.5, 0.3, null, true);
        b.world.spawnParticle(Particle.END_ROD, b.home.clone().add(0, 3 * k, 0), 6, 1.0, 1.5, 1.0, 0.05, null, true);
        if (t % 20 == 0) {
            b.world.playSound(b.home, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 10.0f, 0.5f + (float) k * 0.4f);
        }

        if (t >= emergeTicks) {
            b.emerging = false;
            b.lastHitTick = t;
            b.cooldown = 100;
            b.body.setAI(true);
            b.body.setInvulnerable(false);
            spawnCrystals(b);
            refreshBar(b);
            b.world.spawnParticle(Particle.EXPLOSION_EMITTER, b.home, 6, 2, 1, 2, 0, null, true);
            b.world.playSound(b.home, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 12.0f, 0.5f);
            plugin.log("Typhon hazır.");
        }
    }

    private void spawnCrystals(Boss b) {
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
    private void syncParts(Boss b, Location bl) {
        Location last = b.lastSync;
        if (last != null && last.getWorld() == bl.getWorld()
                && last.distanceSquared(bl) < 0.0004 && Math.abs(last.getYaw() - bl.getYaw()) < 1.0f) {
            return;
        }
        b.lastSync = bl.clone();
        for (BlockDisplay d : b.displays) {
            if (d.isValid()) {
                d.teleportAsync(bl);
            }
        }
    }

    private void orbitCrystals(Boss b, Location bl, int t) {
        int i = 0;
        for (EnderCrystal c : b.crystals) {
            if (!c.isValid()) {
                continue;
            }
            double a = t * 0.05 + i * Math.PI / 2;
            Location at = new Location(b.world,
                    bl.getX() + Math.cos(a) * 3.6,
                    bl.getY() + 5.0 + Math.sin(t * 0.1 + i) * 0.4,
                    bl.getZ() + Math.sin(a) * 3.6);
            c.teleportAsync(at);
            if (t % 5 == 0) {
                c.setBeamTarget(b.healing ? bl.clone().add(0, 3.2, 0) : null);
            }
            i++;
        }
    }

    private void healTick(Boss b, FileConfiguration cfg, int t, double health) {
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

    /** Çıktığı çukurun {roam-limit} blok dışına çıkamaz, çıkarsa geri döner. */
    private void roamTick(Boss b, FileConfiguration cfg, Location bl) {
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
            Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(170, 60, 255), 2.0f);
            for (int i = -6; i <= 6; i++) {
                double a = ang + i * 0.04;
                Location l = new Location(b.world, b.home.getX() + Math.cos(a) * limit, bl.getY() + 0.3,
                        b.home.getZ() + Math.sin(a) * limit);
                b.world.spawnParticle(Particle.DUST, l, 1, 0, 0.5, 0, 0, purple, true);
            }
        }
    }

    /** Bir yere sıkışırsa ya da blok içinde kalırsa kurtarır. */
    private void stuckTick(Boss b, Location bl) {
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

    // ------------------------------------------------------------------ saldırılar

    private void attackTick(Boss b, FileConfiguration cfg, Location bl) {
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
                b.attack = new Attack(2, b.phase >= 3 ? 30 : 36, centers, 3.0, strikeDamage);
                b.world.playSound(bl, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8.0f, 1.2f);
                return;
            }
        }
        double radius = b.phase >= 3 ? 9.0 : 7.0;
        b.attack = new Attack(1, b.phase >= 3 ? 24 : 30, List.of(bl.clone()), radius, slamDamage);
        b.world.playSound(bl, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8.0f, 0.6f);
    }

    private void runAttack(Boss b, FileConfiguration cfg) {
        Attack a = b.attack;
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
            hurt(b.world, c, a.radius, Math.min(a.damage, maxHit));
        }
        b.attack = null;
        int seconds = Math.max(2, cfg.getInt("boss.attack-cooldown-seconds", 8));
        double factor = b.phase == 1 ? 1.0 : b.phase == 2 ? 0.75 : 0.5;
        b.cooldown = (int) (seconds * 20 * factor);
    }

    private void drawRing(World w, Location c, double r, double progress) {
        Particle.DustOptions dust = new Particle.DustOptions(
                Color.fromRGB((int) (120 + 135 * progress), 30, (int) (255 - 200 * progress)), 2.0f);
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

    private void onPhase(Boss b, Location bl) {
        if (b.phase >= 2) {
            Material red = Material.REDSTONE_BLOCK;
            for (int i = 0; i < PARTS.size(); i++) {
                if (PARTS.get(i).kind() != 0) {
                    BlockDisplay d = b.displays.get(i);
                    d.getScheduler().run(plugin, x -> d.setBlock(red.createBlockData()), null);
                }
            }
        }
        setBase(b.body, Attribute.MOVEMENT_SPEED, b.baseSpeed * (1.0 + 0.15 * (b.phase - 1)));
        b.bar.color(b.phase >= 3 ? BossBar.Color.RED : BossBar.Color.PINK);
        b.bar.name(barTitle(b));
        b.world.spawnParticle(Particle.EXPLOSION_EMITTER, bl.clone().add(0, 3, 0), 4, 2, 2, 2, 0, null, true);
        b.world.spawnParticle(Particle.LARGE_SMOKE, bl.clone().add(0, 3, 0), 120, 2, 3, 2, 0.1, null, true);
        b.world.playSound(bl, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 12.0f, 0.6f);
        plugin.log("Typhon evre " + b.phase);
    }

    private void refreshBar(Boss b) {
        double r = plugin.getConfig().getDouble("boss.bar-radius", 150.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (p.getWorld().equals(b.world) && p.getLocation().distanceSquared(b.lastLoc) <= r * r) {
                    p.showBossBar(b.bar);
                } else {
                    p.hideBossBar(b.bar);
                }
            }, null);
        }
    }

    private void hideBar(Boss b) {
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
        Boss b = boss;
        if (b == null) {
            return false;
        }
        finishBoss(b, false);
        return true;
    }

    public void onDeath(EntityDeathEvent e) {
        Boss b = boss;
        if (b == null || !e.getEntity().getUniqueId().equals(b.bodyId)) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        e.getDrops().clear();
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
                e.getDrops().add(new ItemStack(m, n));
                amount -= n;
            }
        }
        e.setDroppedExp(cfg.getInt("boss.exp", 300));
        plugin.log("Typhon öldü.");
        finishBoss(b, true);
    }

    private void finishBoss(Boss b, boolean died) {
        synchronized (b) {
            if (b.removing) {
                return;
            }
            b.removing = true;
        }
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
                world.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 12.0f, 0.4f);
            }
        });

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (BlockDisplay d : b.displays) {
            Transformation scatter = new Transformation(
                    new Vector3f((float) rnd.nextDouble(-4, 4), (float) rnd.nextDouble(1, 5), (float) rnd.nextDouble(-4, 4)),
                    new Quaternionf().rotateXYZ((float) rnd.nextDouble(-3, 3), (float) rnd.nextDouble(-3, 3), (float) rnd.nextDouble(-3, 3)),
                    new Vector3f(0.01f, 0.01f, 0.01f),
                    new Quaternionf());
            d.getScheduler().run(plugin, t -> {
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

    // ------------------------------------------------------------------ olaylar (BossListener çağırır)

    public boolean isBody(Entity e) {
        Boss b = boss;
        return b != null && e.getUniqueId().equals(b.bodyId);
    }

    public boolean isCrystal(Entity e) {
        Boss b = boss;
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
        Boss b = boss;
        return b == null ? null : b.home;
    }

    /** Boss ve kristallere gelen/giden hasar kuralları. */
    public void onDamage(EntityDamageEvent e) {
        Boss b = boss;
        if (b == null) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        Entity victim = e.getEntity();

        // Boss'un verdiği hasara üst sınır (tek vuruşta ölüm olmasın)
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager().getUniqueId().equals(b.bodyId)) {
            e.setDamage(Math.min(e.getDamage(), cfg.getDouble("boss.max-hit", 10.0)));
            return;
        }

        if (victim.getUniqueId().equals(b.bodyId)) {
            if (b.emerging || b.removing) {
                e.setCancelled(true);
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

    /** Hasarı kaydeder ve "son vuruş" zamanını günceller. */
    public void onDamageDone(EntityDamageEvent e) {
        Boss b = boss;
        if (b == null || !e.getEntity().getUniqueId().equals(b.bodyId)) {
            return;
        }
        Player attacker = attackerOf(e);
        if (attacker != null) {
            b.damage.merge(attacker.getUniqueId(), e.getFinalDamage(), Double::sum);
            b.lastHitTick = b.tick;
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

    private void breakCrystal(Boss b, EnderCrystal c) {
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
