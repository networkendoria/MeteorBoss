package com.zyven.meteorboss;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Meteor etkinliğinin tamamını yönetir: düşüş, Boşluk Çukuru, yedek ve kapanma.
 * Folia uyumlu: blok işleri bloğun bulunduğu bölgenin (region) thread'inde,
 * oyuncu işleri oyuncunun kendi thread'inde yapılır.
 */
public final class EventManager {

    private static final long FAST_CLOSE_MS = 5000L;
    private static final long DEATH_CLOSE_MS = 2000L;

    private final MeteorBossPlugin plugin;
    private final BackupStore backup;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final List<Consumer<Session>> emergeHooks = new CopyOnWriteArrayList<>();

    private volatile Session current;
    private volatile BossManager boss;

    public void setBossManager(BossManager boss) {
        this.boss = boss;
    }

    public EventManager(MeteorBossPlugin plugin, BackupStore backup) {
        this.plugin = plugin;
        this.backup = backup;
    }

    /** 2. aşamada boss'u çıkarmak için buraya bağlanacağız (çukur hazır olunca çağrılır). */
    public void addEmergeHook(Consumer<Session> hook) {
        emergeHooks.add(hook);
    }

    public Component text(String miniMessage) {
        return mm.deserialize(miniMessage);
    }

    // ------------------------------------------------------------------ başlatma

    /** Başarılıysa null, değilse oyuncuya gösterilecek hata yazısı döner. */
    public synchronized String start(World world, int x, int z, boolean force) {
        return start(world, x, z, null, force);
    }

    /** fixedY verilirse (oyuncunun ayak yüksekliği) meteor tam o yüksekliğe çarpar, yoksa en üst blok bulunur. */
    public synchronized String start(World world, int x, int z, Integer fixedY, boolean force) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return "Meteor şimdilik sadece normal dünyada (overworld) düşer.";
        }
        if (current != null || (boss != null && boss.isAlive())) {
            return "Bir etkinlik zaten sürüyor. Bitirmek için: /meteor etkinlik -k";
        }
        if (backup.exists()) {
            return "Eski bir çukur yedeği var. Önce /meteor debug yaz.";
        }
        long now = System.currentTimeMillis();
        if (!force) {
            long cooldown = plugin.getConfig().getLong("cooldown-minutes", 30L) * 60_000L;
            long wait = cooldown - (now - plugin.getLastStart());
            if (wait > 0) {
                return "Bekleme süresi: " + formatTime(wait) + " kaldı. (Zorla başlatmak için: /meteor etkinlik -z)";
            }
        }

        int radius = Math.max(6, Math.min(30, plugin.getConfig().getInt("arena-radius", 15)));
        Session s = new Session(world.getName(), x, z, radius);
        s.fixedGroundY = fixedY == null ? null : fixedY - 1;
        current = s;
        plugin.setLastStart(now);
        plugin.log("Etkinlik başladı: " + world.getName() + " " + x + " " + z + " (zorla=" + force + ")");

        int cx = x >> 4;
        int cz = z >> 4;
        world.getChunkAtAsync(cx, cz)
                .thenAccept(chunk -> Bukkit.getRegionScheduler().run(plugin, world, cx, cz, task -> beginFall(s, world)))
                .exceptionally(ex -> {
                    plugin.logError("Düşüş alanı yüklenemedi", ex);
                    s.errors.incrementAndGet();
                    finish(s);
                    return null;
                });
        return null;
    }

    private static String formatTime(long ms) {
        long total = (ms + 999) / 1000;
        long min = total / 60;
        long sec = total % 60;
        return min > 0 ? min + " dk " + sec + " sn" : sec + " sn";
    }

    // ------------------------------------------------------------------ otomatik etkinlik

    private final long bootTime = System.currentTimeMillis();
    private final java.util.concurrent.atomic.AtomicBoolean autoBusy = new java.util.concurrent.atomic.AtomicBoolean();

    /** Her 30 saniyede bir çağrılır (global scheduler). Ayarlıysa kendi kendine meteor düşürür. */
    public void autoTick() {
        try {
            FileConfiguration cfg = plugin.getConfig();
            if (!cfg.getBoolean("auto-event.enabled", false)) {
                return;
            }
            if (current != null || (boss != null && boss.isAlive()) || backup.exists()) {
                return;
            }
            // Belirli saatler varsa (örn. "18:00") sadece o dakikada başlar, yoksa aralık süresine bakar
            List<String> times = cfg.getStringList("auto-event.times");
            String clockKey = null;
            if (!times.isEmpty()) {
                java.time.ZoneId zone;
                try {
                    zone = java.time.ZoneId.of(cfg.getString("auto-event.timezone", "Europe/Istanbul"));
                } catch (Exception e) {
                    zone = java.time.ZoneId.systemDefault();
                }
                java.time.LocalDateTime now = java.time.LocalDateTime.now(zone);
                String hm = String.format("%02d:%02d", now.getHour(), now.getMinute());
                if (!times.contains(hm)) {
                    return;
                }
                clockKey = now.toLocalDate() + " " + hm;
                if (clockKey.equals(plugin.getLastAutoKey())) {
                    return;
                }
            } else {
                long interval = Math.max(1L, cfg.getLong("auto-event.interval-minutes", 120L)) * 60_000L;
                long since = Math.max(plugin.getLastStart(), bootTime);
                if (System.currentTimeMillis() - since < interval) {
                    return;
                }
            }
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (online.size() < Math.max(1, cfg.getInt("auto-event.min-players", 3))) {
                return;
            }
            if (!autoBusy.compareAndSet(false, true)) {
                return;
            }
            if (clockKey != null) {
                plugin.setLastAutoKey(clockKey);
            }
            Player p = online.get(ThreadLocalRandom.current().nextInt(online.size()));
            p.getScheduler().run(plugin, t -> {
                World w = p.getWorld();
                if (w.getEnvironment() != World.Environment.NORMAL) {
                    autoBusy.set(false);
                    return;
                }
                tryAutoSite(w, 5);
            }, () -> autoBusy.set(false));
        } catch (Throwable e) {
            autoBusy.set(false);
            plugin.logError("Otomatik etkinlik hatası", e);
        }
    }

    private void tryAutoSite(World world, int tries) {
        if (tries <= 0) {
            autoBusy.set(false);
            plugin.log("Otomatik etkinlik: uygun yer bulunamadı, tekrar denenecek.");
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location spawn = world.getSpawnLocation();
        double min = cfg.getDouble("auto-event.min-distance", 80.0);
        double max = Math.max(min + 1, cfg.getDouble("auto-event.max-distance", 200.0));
        double avoid = cfg.getDouble("auto-event.avoid-spawn-radius", 300.0);

        // Rastgele bir oyuncunun yakınında, spawn'dan uzak bir nokta seç
        List<Player> inWorld = new ArrayList<>(world.getPlayers());
        if (inWorld.isEmpty()) {
            autoBusy.set(false);
            return;
        }
        Player p = inWorld.get(rnd.nextInt(inWorld.size()));
        p.getScheduler().run(plugin, t -> {
            Location pl = p.getLocation();
            double ang = rnd.nextDouble(Math.PI * 2);
            double dist = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.round(pl.getX() + Math.cos(ang) * dist);
            int z = (int) Math.round(pl.getZ() + Math.sin(ang) * dist);
            if (Math.hypot(x - spawn.getX(), z - spawn.getZ()) < avoid) {
                tryAutoSite(world, tries - 1);
                return;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            world.getChunkAtAsync(cx, cz).thenAccept(chunk ->
                    Bukkit.getRegionScheduler().run(plugin, world, cx, cz, task -> {
                        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                        String name = top.getType().name();
                        if (top.isLiquid() || name.contains("ICE") || name.contains("LEAVES")) {
                            tryAutoSite(world, tries - 1);
                            return;
                        }
                        String err = start(world, x, z, false);
                        autoBusy.set(false);
                        if (err != null) {
                            plugin.log("Otomatik etkinlik başlamadı: " + err);
                        }
                    })).exceptionally(ex -> {
                autoBusy.set(false);
                plugin.logError("Otomatik etkinlik alanı yüklenemedi", ex);
                return null;
            });
        }, () -> autoBusy.set(false));
    }

    // ------------------------------------------------------------------ düşüş

    private void beginFall(Session s, World world) {
        try {
            FileConfiguration cfg = plugin.getConfig();
            int x = s.centerX;
            int z = s.centerZ;
            int groundY = s.fixedGroundY != null ? s.fixedGroundY
                    : world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            s.groundY = groundY;

            int fallTicks = Math.max(60, cfg.getInt("fall-ticks", 160));
            int height = Math.max(40, cfg.getInt("fall-height", 120));
            int startY = Math.min(groundY + height, world.getMaxHeight() - 2);
            double endY = groundY + 1;
            Location center = new Location(world, x + 0.5, groundY + 1, z + 0.5);
            int titleRadius = cfg.getInt("title-radius", 0);

            Title title = Title.title(
                    mm.deserialize(cfg.getString("messages.title", "<gold>METEOR")),
                    mm.deserialize(cfg.getString("messages.subtitle", "")),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800)));
            forPlayers(world, center, titleRadius, p -> p.showTitle(title));

            int[] tick = {0};
            ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, x >> 4, z >> 4, t -> {
                if (s.cancelled) {
                    t.cancel();
                    return;
                }
                try {
                    tick[0]++;
                    fallTick(s, world, center, tick[0], fallTicks, startY, endY, titleRadius);
                    if (tick[0] >= fallTicks) {
                        t.cancel();
                        impact(s, world, groundY);
                    }
                } catch (Throwable e) {
                    t.cancel();
                    s.errors.incrementAndGet();
                    plugin.logError("Meteor düşüşünde hata", e);
                    finish(s);
                }
            }, 1L, 1L);
            s.track(task);
        } catch (Throwable e) {
            s.errors.incrementAndGet();
            plugin.logError("Düşüş başlatılamadı", e);
            finish(s);
        }
    }

    private void fallTick(Session s, World world, Location center, int tick, int fallTicks,
                          int startY, double endY, int titleRadius) {
        int x = s.centerX;
        int z = s.centerZ;
        double p = Math.min(1.0, tick / (double) fallTicks);
        double y = startY - (startY - endY) * p * p;
        Location head = new Location(world, x + 0.5, y, z + 0.5);

        // Büyük ateş topu ve alev kuyruğu
        world.spawnParticle(Particle.FLAME, head, 90, 1.8, 1.8, 1.8, 0.03, null, true);
        world.spawnParticle(Particle.LAVA, head, 8, 1.5, 1.5, 1.5, 0, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, head.clone().add(0, 3, 0), 40, 1.5, 3.0, 1.5, 0.02, null, true);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, head.clone().add(0, 7, 0), 30, 1.0, 6.0, 1.0, 0.01, null, true);

        // Etrafında dönen ateş halkaları
        double ang = tick * 0.35;
        for (int i = 0; i < 3; i++) {
            double a = ang + i * Math.PI * 2 / 3;
            Location r = head.clone().add(Math.cos(a) * 4.5, Math.sin(a * 0.7) * 1.5, Math.sin(a) * 4.5);
            world.spawnParticle(Particle.FLAME, r, 4, 0.1, 0.1, 0.1, 0.01, null, true);
        }

        // Yerdeki uyarı halkası (çukurun nereye açılacağını gösterir)
        if (tick % 5 == 0) {
            Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(160, 0, 255), 2.5f);
            int pts = Math.max(40, s.radius * 4);
            for (int i = 0; i < pts; i++) {
                double a = i * Math.PI * 2 / pts;
                Location r = new Location(world, x + 0.5 + Math.cos(a) * s.radius, endY + 0.3, z + 0.5 + Math.sin(a) * s.radius);
                world.spawnParticle(Particle.DUST, r, 1, 0, 0, 0, 0, purple, true);
            }
        }

        if (tick % 10 == 0) {
            world.playSound(head, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 8.0f, 0.4f);
        }

        // Yaklaştıkça yıldırım (aynı parça içinde kalsın diye sınırlı)
        if (tick > fallTicks / 2 && tick % 14 == 0) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int minX = (x >> 4) * 16;
            int minZ = (z >> 4) * 16;
            int lx = Math.max(minX, Math.min(minX + 15, x + rnd.nextInt(-14, 15)));
            int lz = Math.max(minZ, Math.min(minZ + 15, z + rnd.nextInt(-14, 15)));
            world.strikeLightningEffect(new Location(world, lx + 0.5, endY, lz + 0.5));
        }

        // Geri sayım (ekranın altında)
        if (tick % 20 == 0) {
            int left = Math.max(0, (fallTicks - tick) / 20);
            Component bar = mm.deserialize(plugin.getConfig().getString("messages.countdown", "%s")
                    .replace("%s", String.valueOf(left)));
            forPlayers(world, center, titleRadius, pl -> pl.sendActionBar(bar));
        }
    }

    // ------------------------------------------------------------------ çarpma ve çukur

    private void impact(Session s, World world, int groundY) {
        int x = s.centerX;
        int z = s.centerZ;
        Location c = new Location(world, x + 0.5, groundY + 1, z + 0.5);

        synchronized (this) {
            if (s.cancelled) {
                return;
            }
            s.state = Session.State.BUILDING;
        }

        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 14, 5, 2, 5, 0, null, true);
        world.spawnParticle(Particle.FLAME, c, 500, 6, 3, 6, 0.25, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, c, 300, 6, 4, 6, 0.15, null, true);
        world.spawnParticle(Particle.END_ROD, c.clone().add(0, 2, 0), 200, 5, 4, 5, 0.3, null, true);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 14.0f, 0.5f);
        world.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 14.0f, 0.6f);
        world.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 10.0f, 0.5f);

        shockwave(s, world, c);
        pushNearbyPlayers(world, c);

        Map<Long, List<ArenaPlanner.Change>> plan = ArenaPlanner.plan(
                x, z, groundY, s.radius, System.nanoTime(), world.getMinHeight(), world.getMaxHeight());

        try {
            backup.begin(s);
        } catch (IOException e) {
            s.errors.incrementAndGet();
            plugin.logError("Yedek dosyası açılamadı, çukur açılmadı", e);
            finish(s);
            return;
        }

        s.pending.set(plan.size());
        for (Map.Entry<Long, List<ArenaPlanner.Change>> entry : plan.entrySet()) {
            int cx = (int) (entry.getKey() >> 32);
            int cz = (int) (long) entry.getKey();
            List<ArenaPlanner.Change> list = entry.getValue();
            world.getChunkAtAsync(cx, cz)
                    .thenAccept(ch -> Bukkit.getRegionScheduler().run(plugin, world, cx, cz,
                            task -> applyChunk(s, world, cx, cz, list)))
                    .exceptionally(ex -> {
                        s.errors.incrementAndGet();
                        plugin.logError("Parça yüklenemedi: " + cx + " " + cz, ex);
                        chunkBuilt(s);
                        return null;
                    });
        }
    }

    private void shockwave(Session s, World world, Location c) {
        int[] t = {0};
        ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, s.centerX >> 4, s.centerZ >> 4, tk -> {
            t[0]++;
            double r = t[0] * 1.8;
            if (r > s.radius + 16 || s.cancelled) {
                tk.cancel();
                return;
            }
            int pts = Math.min(200, (int) (r * 6) + 12);
            for (int i = 0; i < pts; i++) {
                double a = i * Math.PI * 2 / pts;
                Location l = new Location(world, c.getX() + Math.cos(a) * r, c.getY() + 0.3, c.getZ() + Math.sin(a) * r);
                world.spawnParticle(Particle.LARGE_SMOKE, l, 1, 0.1, 0.1, 0.1, 0.02, null, true);
                if (i % 2 == 0) {
                    world.spawnParticle(Particle.FLAME, l, 1, 0.1, 0.2, 0.1, 0.02, null, true);
                }
            }
        }, 1L, 1L);
        s.track(task);
    }

    private void pushNearbyPlayers(World world, Location c) {
        FileConfiguration cfg = plugin.getConfig();
        int radius = cfg.getInt("shock-radius", 28);
        double damage = cfg.getDouble("shock-damage", 2.0);
        double power = cfg.getDouble("shock-knockback", 1.4);
        forPlayers(world, c, radius, p -> {
            Vector away = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) {
                away = new Vector(1, 0, 0);
            }
            double dist = p.getLocation().distance(c);
            double factor = Math.max(0.3, 1.0 - dist / Math.max(1, radius));
            Vector push = away.normalize().multiply(power * factor);
            push.setY(0.5 * factor + 0.2);
            p.setVelocity(push);
            if (damage > 0 && dist < radius * 0.6) {
                p.damage(damage);
            }
        });
    }

    /** Bu parçadaki değişiklikleri yedekler ve uygular (parçanın bölge thread'inde çalışır). */
    private void applyChunk(Session s, World world, int cx, int cz, List<ArenaPlanner.Change> changes) {
        try {
            if (s.cancelled) {
                return;
            }
            List<ArenaPlanner.Change> todo = new ArrayList<>();
            List<Session.Saved> saved = new ArrayList<>();
            for (ArenaPlanner.Change ch : changes) {
                Block b = world.getBlockAt(ch.x(), ch.y(), ch.z());
                Material cur = b.getType();
                if (!canReplace(b)) {
                    continue;
                }
                if (ch.onlyIfSolid() && (cur.isAir() || b.isLiquid())) {
                    continue;
                }
                if (cur == ch.material() || (cur.isAir() && ch.material().isAir())) {
                    continue;
                }
                todo.add(ch);
                saved.add(new Session.Saved(ch.x(), ch.y(), ch.z(), b.getBlockData().getAsString()));
            }
            if (saved.isEmpty()) {
                return;
            }
            backup.append(saved); // önce yedek, sonra değişiklik
            s.saved.put(Session.chunkKey(cx, cz), saved);
            for (ArenaPlanner.Change ch : todo) {
                world.getBlockAt(ch.x(), ch.y(), ch.z()).setType(ch.material(), false);
            }
        } catch (Throwable e) {
            s.errors.incrementAndGet();
            plugin.logError("Çukur parçası uygulanamadı: " + cx + " " + cz, e);
        } finally {
            chunkBuilt(s);
        }
    }

    private static boolean canReplace(Block b) {
        Material type = b.getType();
        if (type.isAir()) {
            return true;
        }
        if (type.getHardness() < 0) { // bedrock, bariyer vb.
            return false;
        }
        return !(b.getState(false) instanceof TileState); // sandık, tabela vb. bozulmasın
    }

    private void chunkBuilt(Session s) {
        if (s.pending.decrementAndGet() == 0) {
            onBuilt(s);
        }
    }

    private synchronized void onBuilt(Session s) {
        if (s.cancelled) {
            startClose(s, true);
            return;
        }
        s.state = Session.State.ACTIVE;
        plugin.log("Cehennem Çukuru hazır.");
        for (Consumer<Session> hook : emergeHooks) {
            try {
                hook.accept(s);
            } catch (Throwable e) {
                plugin.logError("Boss çıkış kancası hata verdi", e);
            }
        }
        long delay = Math.max(1L, plugin.getConfig().getLong("close-delay-seconds", 10L) * 20L);
        s.track(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> startClose(s, false), delay));
    }

    // ------------------------------------------------------------------ kapanma

    private synchronized void startClose(Session s, boolean fast) {
        startClose(s, fast, 0L);
    }

    private synchronized void startClose(Session s, boolean fast, long overrideMs) {
        if (s.state == Session.State.CLOSING || s.state == Session.State.DONE) {
            return;
        }
        s.state = Session.State.CLOSING;
        s.fastClose = fast;
        s.closeStart = System.currentTimeMillis();
        s.closeDuration = overrideMs > 0 ? overrideMs : fast ? FAST_CLOSE_MS
                : Math.max(10L, plugin.getConfig().getLong("close-duration-seconds", 120L)) * 1000L;

        World world = Bukkit.getWorld(s.worldName);
        if (world == null) {
            s.errors.incrementAndGet();
            plugin.log("Dünya bulunamadı: " + s.worldName + " (yedek dosyası saklandı)");
            finish(s);
            return;
        }
        if (s.saved.isEmpty()) {
            finish(s);
            return;
        }

        plugin.log("Çukur kapanıyor (" + (s.closeDuration / 1000) + " sn).");
        s.restoreChunks.set(s.saved.size());
        for (Map.Entry<Long, List<Session.Saved>> entry : s.saved.entrySet()) {
            int cx = (int) (entry.getKey() >> 32);
            int cz = (int) (long) entry.getKey();

            List<Session.Due> list = new ArrayList<>();
            for (Session.Saved sv : entry.getValue()) {
                double dx = sv.x() + 0.5 - s.centerX;
                double dz = sv.z() + 0.5 - s.centerZ;
                double dist = Math.sqrt(dx * dx + dz * dz);
                list.add(new Session.Due(sv, 1.0 - Math.min(1.0, dist / (s.radius + 3.0)))); // kenarlar önce
            }
            list.sort(Comparator.comparingDouble(Session.Due::fraction));
            Session.RestoreState rs = new Session.RestoreState(list);

            world.getChunkAtAsync(cx, cz)
                    .thenAccept(ch -> s.track(Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, cx, cz,
                            task -> restoreTick(s, world, cx, cz, rs, task), 1L, 10L)))
                    .exceptionally(ex -> {
                        s.errors.incrementAndGet();
                        plugin.logError("Kapanış parçası yüklenemedi: " + cx + " " + cz, ex);
                        restoreChunkDone(s);
                        return null;
                    });
        }
    }

    private void restoreTick(Session s, World world, int cx, int cz, Session.RestoreState rs, ScheduledTask task) {
        if (rs.done) {
            task.cancel();
            return;
        }
        try {
            double elapsed = Math.min(1.0,
                    (System.currentTimeMillis() - s.closeStart) / (double) Math.max(1L, s.closeDuration));
            int smoke = 0;
            while (rs.index < rs.list.size() && rs.list.get(rs.index).fraction() <= elapsed + 1e-9) {
                Session.Saved sv = rs.list.get(rs.index++).block();
                try {
                    Block b = world.getBlockAt(sv.x(), sv.y(), sv.z());
                    b.setBlockData(Bukkit.createBlockData(sv.data()), false);
                    if (smoke < 4 && ThreadLocalRandom.current().nextInt(12) == 0) {
                        smoke++;
                        world.spawnParticle(Particle.LARGE_SMOKE, b.getLocation().add(0.5, 1, 0.5), 3, 0.3, 0.3, 0.3, 0.01, null, true);
                    }
                } catch (RuntimeException e) {
                    s.errors.incrementAndGet();
                    plugin.logError("Blok geri konulamadı", e);
                }
            }
            if (smoke > 0) {
                world.playSound(new Location(world, cx * 16 + 8, s.groundY, cz * 16 + 8),
                        Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.5f, 0.5f);
            }
            pushPlayersOut(world, cx, cz);

            if (rs.index >= rs.list.size()) {
                rs.done = true;
                task.cancel();
                restoreChunkDone(s);
            }
        } catch (Throwable e) {
            s.errors.incrementAndGet();
            plugin.logError("Çukur kapanırken hata", e);
            if (!rs.done) {
                rs.done = true;
                task.cancel();
                restoreChunkDone(s);
            }
        }
    }

    /** Dolan bloğun içinde kalan oyuncuyu yukarı taşır. */
    private void pushPlayersOut(World world, int cx, int cz) {
        Chunk chunk = world.getChunkAt(cx, cz);
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Player p)) {
                continue;
            }
            Location loc = p.getLocation();
            Block feet = loc.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            if (feet.getType().isSolid() || head.getType().isSolid()) {
                int y = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                p.teleportAsync(new Location(world, loc.getX(), y, loc.getZ(), loc.getYaw(), loc.getPitch()));
            }
        }
    }

    private void restoreChunkDone(Session s) {
        if (s.restoreChunks.decrementAndGet() == 0) {
            finish(s);
        }
    }

    private void finish(Session s) {
        if (!s.finished.compareAndSet(false, true)) {
            return;
        }
        s.state = Session.State.DONE;
        s.cancelTasks();
        synchronized (this) {
            if (current == s) {
                current = null;
            }
        }
        if (s.errors.get() == 0) {
            backup.delete();
            plugin.log("Etkinlik bitti, çukur eski haline döndü.");
        } else {
            plugin.log("Etkinlik bitti ama hata oldu, yedek dosyası saklandı. /meteor debug yaz.");
        }
    }

    // ------------------------------------------------------------------ komutlar için

    /** /meteor etkinlik -k : her şeyi bitirir, çukur 5 saniyede kapanır. */
    /** Boss ölünce çukur 2 saniyede kapanır. */
    public synchronized void bossDied() {
        Session s = current;
        if (s == null) {
            return;
        }
        switch (s.state) {
            case ACTIVE -> {
                s.cancelTasks();
                startClose(s, true, DEATH_CLOSE_MS);
            }
            case CLOSING -> {
                s.closeStart = System.currentTimeMillis();
                s.closeDuration = DEATH_CLOSE_MS;
            }
            default -> {
            }
        }
        plugin.log("Boss öldü, çukur " + (DEATH_CLOSE_MS / 1000) + " saniyede kapanıyor.");
    }

    public synchronized boolean forceEnd() {
        boolean bossGone = boss != null && boss.removeNow();
        Session s = current;
        if (s == null) {
            return bossGone;
        }
        s.cancelled = true;
        switch (s.state) {
            case FALLING -> {
                s.cancelTasks();
                finish(s);
            }
            case BUILDING -> {
                // yapım bitince onBuilt hızlı kapanışı başlatır
            }
            case ACTIVE -> {
                s.cancelTasks();
                startClose(s, true);
            }
            case CLOSING -> {
                s.closeStart = System.currentTimeMillis();
                s.closeDuration = FAST_CLOSE_MS;
            }
            default -> {
            }
        }
        plugin.log("Etkinlik zorla sonlandırıldı.");
        return true;
    }

    /** Sunucu açılırken yarım kalmış bir çukur varsa geri kurar. */
    public synchronized void recoverOnStartup() {
        if (current != null || !backup.exists()) {
            return;
        }
        try {
            Session s = backup.load();
            if (Bukkit.getWorld(s.worldName) == null) {
                throw new IOException("Dünya bulunamadı: " + s.worldName);
            }
            current = s;
            plugin.log("Yarım kalmış çukur bulundu, geri kuruluyor.");
            startClose(s, true);
        } catch (Exception e) {
            plugin.logError("Yedek geri kurulamadı", e);
            DebugReport.write(plugin, List.of("Sunucu açılırken yedek geri kurulamadı."), e);
        }
    }

    /** /meteor debug : düzeltmeyi dener, düzelmezse gün-ay-saat adlı dosya yazar. */
    public void debug(CommandSender sender) {
        List<String> notes = new ArrayList<>();
        Throwable error = null;

        synchronized (this) {
            Session s = current;
            if (s != null) {
                notes.add("Aktif etkinlik: durum=" + s.state + " hata=" + s.errors.get()
                        + " merkez=" + s.worldName + " " + s.centerX + " " + s.centerZ);
                forceEnd();
                sender.sendMessage(mm.deserialize("<yellow>Aktif etkinlik bitiriliyor, çukur geri kuruluyor..."));
            } else if (backup.exists()) {
                try {
                    Session loaded = backup.load();
                    if (Bukkit.getWorld(loaded.worldName) == null) {
                        throw new IOException("Dünya bulunamadı: " + loaded.worldName);
                    }
                    current = loaded;
                    notes.add("Yedek dosyası bulundu: " + loaded.worldName + " " + loaded.centerX + " " + loaded.centerZ);
                    startClose(loaded, true);
                    sender.sendMessage(mm.deserialize("<yellow>Yedek bulundu, çukur geri kuruluyor..."));
                } catch (Exception e) {
                    error = e;
                }
            } else {
                sender.sendMessage(mm.deserialize("<green>Düzeltilecek bir sorun bulunamadı."));
                return;
            }
        }

        if (error != null) {
            reportFailure(sender, notes, error);
            return;
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            if (current != null || backup.exists()) {
                reportFailure(sender, notes, null);
            } else {
                sender.sendMessage(mm.deserialize("<green>Düzeltildi, çukur eski haline döndü."));
            }
        }, 400L);
    }

    private void reportFailure(CommandSender sender, List<String> notes, Throwable error) {
        notes.add("Düzeltme başarısız oldu.");
        java.io.File file = DebugReport.write(plugin, notes, error);
        sender.sendMessage(mm.deserialize("<red>Düzelmedi. Hata dosyası kaydedildi: <white>" + file.getName()
                + "<red> (plugins/MeteorBoss/debug klasöründe). Bu dosyayı bana at."));
    }

    // ------------------------------------------------------------------ yardımcı

    /** Oyuncuları kendi thread'lerinde işler (Folia kuralı). radius 0 = dünyadaki herkes. */
    private void forPlayers(World world, Location center, int radius, Consumer<Player> action) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (!p.getWorld().equals(world)) {
                    return;
                }
                if (radius > 0 && p.getLocation().distanceSquared(center) > (double) radius * radius) {
                    return;
                }
                action.accept(p);
            }, null);
        }
    }
}
