package com.zyven.meteorboss;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Çukurun değiştirdiği blokların eski halini diske yazar, sunucu kapansa bile geri kurulabilsin. */
final class BackupStore {

    private final File file;

    BackupStore(File dataFolder) {
        this.file = new File(new File(dataFolder, "backup"), "arena.dat");
    }

    boolean exists() {
        return file.isFile();
    }

    synchronized void begin(Session s) throws IOException {
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Yedek klasörü açılamadı: " + dir);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            w.write("H\t" + s.worldName + "\t" + s.centerX + "\t" + s.centerZ + "\t" + s.radius + "\t" + s.groundY + "\n");
        }
    }

    synchronized void append(List<Session.Saved> list) throws IOException {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            for (Session.Saved b : list) {
                w.write(b.x() + "\t" + b.y() + "\t" + b.z() + "\t" + b.data() + "\n");
            }
        }
    }

    synchronized Session load() throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith("H\t")) {
            throw new IOException("Yedek dosyası bozuk");
        }
        String[] h = lines.get(0).split("\t");
        Session s;
        try {
            s = new Session(h[1], Integer.parseInt(h[2]), Integer.parseInt(h[3]), Integer.parseInt(h[4]));
            s.groundY = Integer.parseInt(h[5]);
        } catch (RuntimeException e) {
            throw new IOException("Yedek dosyasının başlığı okunamadı", e);
        }
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\t", 4);
            if (p.length < 4) {
                continue;
            }
            try {
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                s.saved.computeIfAbsent(Session.chunkKey(x >> 4, z >> 4), k -> new ArrayList<>())
                        .add(new Session.Saved(x, y, z, p[3]));
            } catch (NumberFormatException ignored) {
                // bozuk satırı atla
            }
        }
        s.state = Session.State.ACTIVE;
        return s;
    }

    synchronized void delete() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // silinemediyse bir sonraki açılışta tekrar denenir
        }
    }
}
