package com.zyven.meteorboss;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** /meteor debug düzeltemezse gün-ay-saat adıyla bir hata dosyası yazar. */
final class DebugReport {

    private DebugReport() {
    }

    static File write(MeteorBossPlugin plugin, List<String> notes, Throwable error) {
        File dir = new File(plugin.getDataFolder(), "debug");
        dir.mkdirs();
        LocalDateTime now = LocalDateTime.now();
        String base = now.format(DateTimeFormatter.ofPattern("dd-MM_HH-mm"));
        File file = new File(dir, base + ".txt");
        if (file.exists()) {
            file = new File(dir, base + "-" + now.format(DateTimeFormatter.ofPattern("ss")) + ".txt");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("MeteorBoss debug raporu\n");
        sb.append("Zaman: ").append(now).append('\n');
        sb.append("Eklenti sürümü: ").append(plugin.getPluginMeta().getVersion()).append('\n');
        sb.append("Sunucu: ").append(Bukkit.getVersion()).append('\n');
        sb.append("\n-- Notlar --\n");
        for (String n : notes) {
            sb.append(n).append('\n');
        }
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            sb.append("\n-- Hata --\n").append(sw).append('\n');
        }
        sb.append("\n-- Son kayıtlar --\n");
        for (String line : plugin.recentLog()) {
            sb.append(line).append('\n');
        }

        try {
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.logError("Debug dosyası yazılamadı", e);
        }
        return file;
    }
}
