package com.zyven.meteorboss;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Tek bir meteor etkinliğinin durumu. */
public final class Session {

    public enum State { FALLING, BUILDING, ACTIVE, CLOSING, DONE }

    /** Değiştirilen bir bloğun eski hali. */
    public record Saved(int x, int y, int z, String data) {}

    record Due(Saved block, double fraction) {}

    /** Bir parçanın (chunk) geri kurulum durumu. Sadece o parçanın bölge thread'inde değişir. */
    static final class RestoreState {
        final List<Due> list;
        int index = 0;
        volatile boolean done = false;

        RestoreState(List<Due> list) {
            this.list = list;
        }
    }

    public final String worldName;
    public final int centerX;
    public final int centerZ;
    public final int radius;
    public final long startedAt = System.currentTimeMillis();

    public volatile int groundY;
    /** Komutta y verildiyse zemin yüksekliği buradan alınır. */
    public volatile Integer fixedGroundY = null;
    public volatile State state = State.FALLING;
    public volatile boolean cancelled = false;
    public volatile boolean fastClose = false;
    public volatile long closeStart = 0L;
    public volatile long closeDuration = 300_000L;

    final Map<Long, List<Saved>> saved = new ConcurrentHashMap<>();
    final Map<Long, RestoreState> restore = new ConcurrentHashMap<>();
    final List<ScheduledTask> tasks = new CopyOnWriteArrayList<>();
    final AtomicInteger pending = new AtomicInteger();
    final AtomicInteger restoreChunks = new AtomicInteger();
    final AtomicInteger errors = new AtomicInteger();
    final AtomicBoolean finished = new AtomicBoolean(false);

    public Session(String worldName, int centerX, int centerZ, int radius) {
        this.worldName = worldName;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
    }

    void track(ScheduledTask task) {
        if (task != null) {
            tasks.add(task);
        }
    }

    void cancelTasks() {
        for (ScheduledTask task : tasks) {
            try {
                task.cancel();
            } catch (Exception ignored) {
                // zaten bitmiş olabilir
            }
        }
        tasks.clear();
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
