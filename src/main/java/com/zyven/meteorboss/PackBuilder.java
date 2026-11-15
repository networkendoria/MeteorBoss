package com.zyven.meteorboss;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Typhon kaynak paketini (resource pack) üretir: modeller, dokular, pack.mcmeta.
 * Sunucuya bağımlılığı yoktur; main() ile de çalıştırılabilir.
 * Her grup (kafa, kol, bacak...) 3 evre için ayrı modeldir: typhon:&lt;grup&gt;_&lt;evre&gt;.
 */
final class PackBuilder {

    private PackBuilder() {}

    static final String NS = "typhon";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "typhon-pack.zip");
        byte[] zip = build();
        Files.write(out, zip);
        System.out.println("Yazıldı: " + out + " (" + zip.length + " bayt)");
        System.out.println("sha1: " + sha1(zip));
    }

    static String sha1(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static byte[] build() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", ("{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": 75,\n"
                + "    \"min_format\": 34,\n"
                + "    \"max_format\": 999,\n"
                + "    \"supported_formats\": {\"min_inclusive\": 34, \"max_inclusive\": 999},\n"
                + "    \"description\": \"Typhon - MeteorBoss modeli\"\n"
                + "  }\n}\n").getBytes(StandardCharsets.UTF_8));

        for (String name : new String[]{"obsidian", "crying", "bone", "brick", "vein", "vein_red",
                "eye", "eye_red", "crystal", "crystal_red", "magma"}) {
            files.put("assets/" + NS + "/textures/item/" + name + ".png", png(texture(name)));
        }

        for (int g = 0; g < TyphonModel.GROUPS; g++) {
            for (int v = 1; v <= 3; v++) {
                String id = TyphonModel.GROUP_NAMES[g] + "_" + v;
                files.put("assets/" + NS + "/models/item/" + id + ".json", model(g, v).getBytes(StandardCharsets.UTF_8));
                files.put("assets/" + NS + "/items/" + id + ".json",
                        ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NS + ":item/" + id + "\"}}\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0L); // her üretimde aynı sha1 çıksın
                zip.putNextEntry(ze);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ model

    private static String f(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private static double ux(double p) {
        return (p / TyphonModel.PACK_SCALE + 0.5) * 16.0;
    }

    private static double uy(double p) {
        return ((p - TyphonModel.PACK_SHIFT_Y) / TyphonModel.PACK_SCALE + 0.5) * 16.0;
    }

    private static String texKey(TyphonModel.Part p, int variant) {
        boolean red = variant >= 2;
        return switch (p.kind()) {
            case 1 -> red ? "vein_red" : "vein";
            case 2 -> red ? "eye_red" : "eye";
            case 3 -> red ? "crystal_red" : "crystal";
            case 4 -> "magma";
            default -> switch (p.mat().name()) {
                case "CRYING_OBSIDIAN" -> "crying";
                case "END_STONE" -> "bone";
                case "END_STONE_BRICKS" -> "brick";
                default -> "obsidian";
            };
        };
    }

    private static boolean glows(TyphonModel.Part p) {
        return p.kind() != 0;
    }

    private static String model(int group, int variant) {
        StringBuilder sb = new StringBuilder();
        Map<String, Boolean> used = new LinkedHashMap<>();
        StringBuilder elements = new StringBuilder();
        boolean first = true;
        for (TyphonModel.Part p : TyphonModel.PARTS) {
            if (p.group() != group || p.minPhase() > variant) {
                continue;
            }
            String key = texKey(p, variant);
            used.put(key, true);

            double x0 = ux(p.cx() - p.sx() / 2);
            double x1 = ux(p.cx() + p.sx() / 2);
            double y0 = uy(p.cy());
            double y1 = uy(p.cy() + p.sy());
            double z0 = ux(p.cz() - p.sz() / 2);
            double z1 = ux(p.cz() + p.sz() / 2);
            double wx = Math.min(16, x1 - x0);
            double wy = Math.min(16, y1 - y0);
            double wz = Math.min(16, z1 - z0);

            if (!first) {
                elements.append(",\n");
            }
            first = false;
            elements.append("    {\"from\":[").append(f(x0)).append(',').append(f(y0)).append(',').append(f(z0))
                    .append("],\"to\":[").append(f(x1)).append(',').append(f(y1)).append(',').append(f(z1)).append(']');

            double deg = Math.toDegrees(p.roll());
            double angle = Math.max(-45, Math.min(45, Math.round(deg / 22.5) * 22.5));
            if (angle != 0) {
                elements.append(",\"rotation\":{\"origin\":[").append(f((x0 + x1) / 2)).append(',')
                        .append(f((y0 + y1) / 2)).append(',').append(f((z0 + z1) / 2))
                        .append("],\"axis\":\"z\",\"angle\":").append(f(angle)).append('}');
            }
            if (glows(p)) {
                elements.append(",\"light_emission\":15");
            }
            elements.append(",\"faces\":{");
            String t = "\"texture\":\"#" + key + "\"";
            elements.append("\"north\":{\"uv\":[0,0,").append(f(wx)).append(',').append(f(wy)).append("],").append(t).append("},");
            elements.append("\"south\":{\"uv\":[0,0,").append(f(wx)).append(',').append(f(wy)).append("],").append(t).append("},");
            elements.append("\"east\":{\"uv\":[0,0,").append(f(wz)).append(',').append(f(wy)).append("],").append(t).append("},");
            elements.append("\"west\":{\"uv\":[0,0,").append(f(wz)).append(',').append(f(wy)).append("],").append(t).append("},");
            elements.append("\"up\":{\"uv\":[0,0,").append(f(wx)).append(',').append(f(wz)).append("],").append(t).append("},");
            elements.append("\"down\":{\"uv\":[0,0,").append(f(wx)).append(',').append(f(wz)).append("],").append(t).append("}}}");
        }

        sb.append("{\n  \"textures\": {");
        boolean ft = true;
        String particle = null;
        for (String key : used.keySet()) {
            if (!ft) {
                sb.append(',');
            }
            ft = false;
            sb.append("\"").append(key).append("\":\"").append(NS).append(":item/").append(key).append("\"");
            if (particle == null) {
                particle = key;
            }
        }
        if (particle == null) {
            particle = "obsidian";
            sb.append("\"obsidian\":\"").append(NS).append(":item/obsidian\"");
        }
        sb.append(",\"particle\":\"#").append(particle).append("\"},\n  \"elements\": [\n")
                .append(elements).append("\n  ]\n}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ dokular

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static BufferedImage texture(String name) {
        Random rnd = new Random(name.hashCode() * 31L + 7);
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int n = rnd.nextInt(21) - 10;
                int c;
                switch (name) {
                    case "obsidian" -> {
                        c = rgb(22 + n, 12 + n / 2, 36 + n);
                        if (rnd.nextInt(14) == 0) {
                            c = rgb(58, 34, 92);
                        }
                    }
                    case "crying" -> {
                        c = rgb(26 + n, 10 + n / 2, 44 + n);
                        if ((x * 7 + 3) % 11 < 2 && y > 2 + (x * 5) % 6) {
                            c = rgb(120 + n * 2, 36, 205 + n);
                        }
                    }
                    case "bone" -> c = rgb(214 + n, 220 + n, 156 + n);
                    case "brick" -> {
                        c = rgb(222 + n, 227 + n, 168 + n);
                        if (y % 8 == 0 || (x + (y / 8) * 4) % 8 == 0) {
                            c = rgb(168, 174, 118);
                        }
                    }
                    case "vein", "vein_red" -> {
                        boolean red = name.endsWith("red");
                        int d = Math.abs(x - 8);
                        int glow = Math.max(0, 255 - d * 55) + n;
                        c = red ? rgb(70 + glow * 3 / 4, 10 + glow / 5, 20 + glow / 6)
                                : rgb(60 + glow / 2, 20 + glow / 3, 110 + glow * 3 / 5);
                    }
                    case "eye", "eye_red" -> {
                        boolean red = name.endsWith("red");
                        int d = Math.max(Math.abs(x - 8), Math.abs(y - 8));
                        int glow = d < 5 ? 255 : 120;
                        c = red ? rgb(glow, glow / 4 + n, glow / 6) : rgb(glow * 3 / 4 + n, glow / 2, glow);
                    }
                    case "crystal", "crystal_red" -> {
                        boolean red = name.endsWith("red");
                        int facet = ((x + y) / 4 + (x - y + 16) / 5) % 3;
                        int base = 120 + facet * 40 + n;
                        c = red ? rgb(base + 60, 20 + n, 40 + facet * 10) : rgb(base, 70 + facet * 20, 220 + n / 2);
                    }
                    default -> { // magma
                        boolean line = (x + y * 3) % 7 < 2 || (x * 2 - y) % 9 == 0;
                        c = line ? rgb(255, 150 + n * 3, 30) : rgb(100 + n * 2, 24, 4);
                    }
                }
                img.setRGB(x, y, c);
            }
        }
        return img;
    }
}
