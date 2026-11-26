package com.zyven.meteorboss;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Zombie;

import static com.zyven.meteorboss.TyphonModel.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Bir Typhon'un çalışma anındaki tüm durumu (can, evre, animasyon, süper saldırı...). */
final class BossState {
    final Session session;
    final World world;
    final Location home;
    Zombie body;
    UUID bodyId;
    BossBar bar;
    final List<Part> parts = new ArrayList<>();
    final List<Display> displays = new ArrayList<>();
    final List<EnderCrystal> crystals = new CopyOnWriteArrayList<>();
    final Map<UUID, Double> damage = new ConcurrentHashMap<>();

    volatile boolean emerging = true;
    volatile boolean removing = false;
    volatile int lastViewerTick = 0;
    int spawnShield = 0;
    int nextRetarget = 100;
    int tailCd = 120;
    int tailLeft = 0;
    volatile double tailSwing = 0.0;
    final Map<Sound, Integer> lastSnd = new ConcurrentHashMap<>();
    volatile boolean healing = false;
    volatile boolean shield = false;
    volatile int phase = 1;
    volatile int tick = 0;
    volatile int lastHitTick = 0;
    volatile int superState = 0; // 0 bekliyor, 1 hazırlanıyor, 2 yorgun
    volatile boolean swingFlag = false;
    volatile boolean poseNow = false;
    volatile boolean forcePose = false;
    volatile int flinch = 0;
    volatile int roarTicks = 0;
    volatile int lastDing = -100;
    volatile int lastHurt = -100;
    volatile Location lastLoc;

    double maxHealth;
    double healed;
    double healCap;
    double baseSpeed;
    BossAttack attack;
    int cooldown = 100;
    boolean returning;
    Location lastPos;
    int stuck;
    int errors;
    Location lastSync;

    // Süper saldırı
    int superTimer;
    int superLeft;
    int superTotal;
    int superCount;
    int exhaustLeft;

    // Poz ve animasyon
    Location poseLast;
    boolean moving;
    boolean swingRight;
    double raiseL, raiseR, headE, jawE;
    double aArmL, aArmR, aLegL, aLegR, aHead, aJaw, breath, bobL, bobR;
    double wingE, aWing, aTail;
    final long[] sig = new long[GROUPS];
    double orbit;
    int nextAmbient = 200;

    BossState(Session session, World world, Location home) {
        this.session = session;
        this.world = world;
        this.home = home;
        this.lastLoc = home;
    }
}

