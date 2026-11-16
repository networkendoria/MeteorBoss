package com.zyven.meteorboss;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** Typhon'un parça listesi (blok modu ve paket modu aynı veriyi kullanır). Sunucuya bağımlılığı yok. */
final class TyphonModel {

    private TyphonModel() {}

    // Parça grupları (aynı grup birlikte hareket eder)
    static final int G_BODY = 0;
    static final int G_ARM_L = 1;
    static final int G_ARM_R = 2;
    static final int G_LEG_L = 3;
    static final int G_LEG_R = 4;
    static final int G_HEAD = 5;
    static final int G_JAW = 6;
    static final int G_CHEST = 7;
    static final int G_CRY_L = 8;
    static final int G_CRY_R = 9;
    static final int G_WING_L = 10;
    static final int G_WING_R = 11;
    static final int G_TAIL = 12;
    static final int GROUPS = 13;
    static final String[] GROUP_NAMES = {"body", "arm_l", "arm_r", "leg_l", "leg_r", "head", "jaw", "chest",
            "cry_l", "cry_r", "wing_l", "wing_r", "tail"};
    /** Paket modunda her grup tek bir modeldir; model bu ölçekle büyütülür. */
    static final double PACK_SCALE = 3.0;
    /** Paket modunda modelin dikey kaydırması (metre). */
    static final double PACK_SHIFT_Y = 3.5;

    /**
     * Görünüm parçası. Ölçüler metre, ayak ortasına göre, cy = alt kenar.
     * kind: 0 normal, 1 damar, 2 göz, 3 süs kristali, 4 çatlak (parlar).
     * roll: kendi ekseninde eğim (radyan). minPhase: bu evreye gelince görünür.
     * detail: 0 her kalitede, 1 orta ve yüksek, 2 sadece yüksek.
     */
    record Part(Material mat, double cx, double cy, double cz,
                        double sx, double sy, double sz,
                        int kind, int group, double roll, int minPhase, int detail) {}

    static final List<Part> PARTS = buildParts();

    private static void mirror(List<Part> l, Material m, double cx, double cy, double cz,
                               double sx, double sy, double sz, int kind, int gl, int gr,
                               double roll, int minPhase, int detail) {
        l.add(new Part(m, -cx, cy, cz, sx, sy, sz, kind, gl, roll, minPhase, detail));
        l.add(new Part(m, cx, cy, cz, sx, sy, sz, kind, gr, -roll, minPhase, detail));
    }

    private static Part one(Material m, double cx, double cy, double cz,
                            double sx, double sy, double sz, int kind, int group,
                            double roll, int minPhase, int detail) {
        return new Part(m, cx, cy, cz, sx, sy, sz, kind, group, roll, minPhase, detail);
    }

    private static List<Part> buildParts() {
        List<Part> l = new ArrayList<>();
        // Nether iblisi paleti
        Material dark = Material.POLISHED_BLACKSTONE;
        Material plate = Material.POLISHED_BLACKSTONE_BRICKS;
        Material helm = Material.BLACKSTONE;
        Material red = Material.RED_NETHER_BRICKS;
        Material nb = Material.NETHER_BRICKS;
        Material bone = Material.BONE_BLOCK;
        Material glow = Material.SHROOMLIGHT;
        Material mag = Material.MAGMA_BLOCK;
        Material wing = Material.NETHER_WART_BLOCK;

        // Bacaklar
        mirror(l, dark, 0.6, 0.45, 0.0, 1.0, 1.75, 1.0, 0, G_LEG_L, G_LEG_R, 0, 1, 0);
        mirror(l, dark, 0.6, 0.0, 0.25, 1.15, 0.45, 1.5, 0, G_LEG_L, G_LEG_R, 0, 1, 0);
        mirror(l, red, 0.6, 1.0, 0.55, 0.9, 0.55, 0.25, 0, G_LEG_L, G_LEG_R, 0, 1, 1);
        mirror(l, glow, 0.6, 0.5, 0.52, 0.1, 1.5, 0.05, 1, G_LEG_L, G_LEG_R, 0, 1, 1);
        mirror(l, plate, 1.1, 0.9, 0.0, 0.3, 0.7, 0.3, 0, G_LEG_L, G_LEG_R, 0.3, 1, 2);
        for (double cx0 : new double[]{-0.3, 0.0, 0.3}) {
            mirror(l, bone, 0.6 + cx0, 0.0, 0.95, 0.2, 0.3, 0.45, 0, G_LEG_L, G_LEG_R, 0, 1, 2);
        }

        // Kalça, karın, kemer, bel örtüsü
        l.add(one(dark, 0.0, 1.9, 0.0, 2.5, 0.5, 1.3, 0, G_BODY, 0, 1, 0));
        l.add(one(red, 0.0, 2.4, 0.0, 2.0, 1.0, 1.1, 0, G_BODY, 0, 1, 0));
        l.add(one(glow, 0.0, 2.5, 0.56, 0.12, 0.9, 0.05, 1, G_BODY, 0, 1, 0));
        l.add(one(plate, 0.0, 2.3, 0.0, 2.7, 0.25, 1.45, 0, G_BODY, 0, 1, 1));
        l.add(one(glow, 0.0, 2.2, 0.74, 0.5, 0.4, 0.1, 1, G_BODY, 0, 1, 1));
        l.add(one(red, 0.0, 1.15, 0.72, 1.0, 0.8, 0.1, 0, G_BODY, 0, 1, 1));
        l.add(one(red, 0.0, 1.15, -0.72, 1.0, 0.8, 0.1, 0, G_BODY, 0, 1, 1));

        // Göğüs: parlayan çekirdek, damarlar, kaburgalar, sırt dikenleri
        l.add(one(dark, 0.0, 3.35, 0.0, 2.8, 1.35, 1.4, 0, G_CHEST, 0, 1, 0));
        l.add(one(glow, 0.0, 3.28, 0.72, 0.12, 1.5, 0.05, 1, G_CHEST, 0.62, 1, 0));
        l.add(one(glow, 0.0, 3.28, 0.72, 0.12, 1.5, 0.05, 1, G_CHEST, -0.62, 1, 0));
        l.add(one(red, 0.0, 3.7, 0.7, 1.4, 0.7, 0.12, 0, G_CHEST, 0, 1, 1));
        l.add(one(glow, 0.0, 3.3, 0.74, 0.5, 0.5, 0.1, 2, G_CHEST, 0.785, 1, 0));
        for (double ry : new double[]{3.0, 3.4, 3.8}) {
            mirror(l, plate, 1.5, ry, 0.1, 0.15, 0.2, 1.1, 0, G_CHEST, G_CHEST, 0, 1, 2);
        }
        for (double sy0 : new double[]{2.7, 3.4, 4.1}) {
            l.add(one(helm, 0.0, sy0, -0.78, 0.3, 0.8, 0.3, 0, G_CHEST, 0, 1, 1));
        }
        l.add(one(mag, 0.0, 3.4, -1.0, 1.0, 1.4, 0.7, 3, G_CHEST, 0, 1, 1));

        // Omuz zırhı, kollar, yumruklar, pençeler
        mirror(l, dark, 2.05, 4.2, 0.0, 1.5, 0.95, 1.7, 0, G_ARM_L, G_ARM_R, 0, 1, 0);
        mirror(l, plate, 2.3, 5.1, 0.0, 0.4, 0.7, 0.4, 0, G_ARM_L, G_ARM_R, 0, 1, 1);
        mirror(l, plate, 2.95, 4.5, 0.0, 0.8, 0.3, 0.3, 0, G_ARM_L, G_ARM_R, 0.4, 1, 2);
        mirror(l, plate, 2.9, 4.9, 0.0, 0.6, 0.25, 0.25, 0, G_ARM_L, G_ARM_R, 0.7, 1, 2);
        mirror(l, dark, 2.15, 1.0, 0.0, 0.95, 3.3, 0.95, 0, G_ARM_L, G_ARM_R, 0, 1, 0);
        mirror(l, glow, 2.15, 1.5, 0.5, 0.1, 2.0, 0.05, 1, G_ARM_L, G_ARM_R, 0, 1, 1);
        mirror(l, red, 2.15, 1.2, 0.0, 1.15, 0.5, 1.15, 0, G_ARM_L, G_ARM_R, 0, 1, 1);
        mirror(l, plate, 2.15, 0.1, 0.1, 1.35, 1.0, 1.3, 0, G_ARM_L, G_ARM_R, 0, 1, 0);
        mirror(l, plate, 2.15, 2.4, 0.62, 0.2, 0.2, 0.5, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        mirror(l, plate, 2.15, 3.0, 0.62, 0.2, 0.2, 0.5, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        for (double fx : new double[]{-0.4, 0.0, 0.4}) {
            mirror(l, bone, 2.15 + fx, 0.0, 0.65, 0.28, 0.5, 0.28, 0, G_ARM_L, G_ARM_R, 0, 1, 1);
        }

        // Yıkıcı kılıç (sağ elde): kabza, kıymık, bıçak, parlayan oluk
        l.add(one(plate, 2.15, 0.3, 0.85, 0.18, 0.8, 0.18, 0, G_ARM_R, 0, 1, 0));
        l.add(one(red, 2.15, 1.1, 0.85, 1.0, 0.18, 0.3, 0, G_ARM_R, 0, 1, 0));
        l.add(one(helm, 2.15, 1.28, 0.85, 0.5, 3.4, 0.16, 0, G_ARM_R, 0, 1, 0));
        l.add(one(glow, 2.15, 1.5, 0.94, 0.14, 3.0, 0.04, 1, G_ARM_R, 0, 1, 0));
        l.add(one(helm, 2.15, 4.68, 0.85, 0.3, 0.45, 0.14, 0, G_ARM_R, 0, 1, 1));

        // Kafa: kara miğfer, ateşli gözler, ağız, taç ve kıvrık boynuzlar
        l.add(one(helm, 0.0, 4.65, 0.0, 1.9, 1.35, 1.7, 0, G_HEAD, 0, 1, 0));
        l.add(one(plate, 0.0, 5.95, 0.0, 1.3, 0.3, 1.2, 0, G_HEAD, 0, 1, 1));
        l.add(one(plate, 0.0, 5.45, 0.82, 1.9, 0.3, 0.3, 0, G_HEAD, 0, 1, 0));
        mirror(l, glow, 0.45, 5.18, 0.87, 0.5, 0.16, 0.07, 2, G_HEAD, G_HEAD, -0.4, 1, 0);
        l.add(one(plate, 0.0, 4.95, 0.86, 0.28, 0.45, 0.1, 0, G_HEAD, 0, 1, 0));
        l.add(one(glow, 0.0, 4.78, 0.87, 0.9, 0.14, 0.06, 1, G_HEAD, 0, 1, 0));
        mirror(l, nb, 0.95, 4.9, 0.2, 0.6, 0.25, 0.25, 0, G_HEAD, G_HEAD, 0.5, 1, 1);
        mirror(l, bone, 0.4, 4.5, 0.78, 0.12, 0.28, 0.12, 0, G_HEAD, G_HEAD, 0, 1, 1);
        // Taç dikenleri
        double[] crownX = {-0.55, -0.28, 0.0, 0.28, 0.55};
        double[] crownH = {0.5, 0.7, 0.95, 0.7, 0.5};
        for (int i = 0; i < 5; i++) {
            l.add(one(plate, crownX[i], 6.1, -0.1, 0.2, crownH[i], 0.2, 0, G_HEAD, -crownX[i] * 0.6, 1, 1));
        }
        // Kıvrık boynuzlar (dışa, yukarı, içe kıvrılır)
        mirror(l, nb, 1.1, 5.1, 0.0, 0.55, 0.5, 0.55, 0, G_HEAD, G_HEAD, 0.78, 1, 0);
        mirror(l, nb, 1.55, 5.42, 0.0, 0.45, 0.55, 0.45, 0, G_HEAD, G_HEAD, 0.5, 1, 0);
        mirror(l, nb, 1.9, 5.9, 0.0, 0.38, 0.6, 0.38, 0, G_HEAD, G_HEAD, 0.2, 1, 0);
        mirror(l, nb, 2.0, 6.42, 0.0, 0.3, 0.55, 0.3, 0, G_HEAD, G_HEAD, -0.2, 1, 1);
        mirror(l, bone, 1.9, 6.9, 0.0, 0.2, 0.5, 0.2, 0, G_HEAD, G_HEAD, -0.5, 1, 1);

        // Çene ve dişler
        l.add(one(nb, 0.0, 4.35, 0.2, 1.3, 0.35, 1.2, 0, G_JAW, 0, 1, 0));
        for (double tx : new double[]{-0.45, -0.15, 0.15, 0.45}) {
            l.add(one(bone, tx, 4.62, 0.75, 0.18, 0.3, 0.12, 0, G_JAW, 0, 1, 2));
        }
        mirror(l, bone, 0.5, 4.36, 0.7, 0.12, 0.3, 0.12, 0, G_JAW, G_JAW, 0, 1, 2);

        // Omuzlarda lav kayaları (evre 2'de kırmızı parlar)
        mirror(l, mag, 2.0, 5.1, -0.35, 1.3, 2.0, 1.3, 3, G_CRY_L, G_CRY_R, 0, 1, 0);
        mirror(l, mag, 2.55, 4.9, 0.3, 0.8, 1.2, 0.8, 3, G_CRY_L, G_CRY_R, 0.4, 1, 1);

        // Çatlaklar ve kor (evre 2 ve 3'te görünür)
        l.add(one(mag, -0.8, 3.5, 0.72, 0.45, 0.5, 0.05, 4, G_CHEST, 0.2, 2, 1));
        l.add(one(mag, 0.9, 3.85, 0.72, 0.4, 0.35, 0.05, 4, G_CHEST, -0.3, 2, 1));
        l.add(one(mag, 0.4, 2.6, 0.57, 0.35, 0.45, 0.05, 4, G_BODY, 0.2, 3, 1));
        l.add(one(mag, -2.15, 2.0, 0.49, 0.5, 0.7, 0.05, 4, G_ARM_L, 0, 2, 2));
        l.add(one(mag, 2.15, 3.0, 0.49, 0.5, 0.7, 0.05, 4, G_ARM_R, 0, 2, 2));
        mirror(l, mag, 0.6, 0.9, 0.52, 0.4, 0.6, 0.05, 4, G_LEG_L, G_LEG_R, 0, 3, 2);
        l.add(one(mag, 0.3, 5.5, 0.9, 0.08, 0.6, 0.05, 4, G_HEAD, 0.4, 2, 2));

        // Kanatlar: kemik kolları + kızıl zar. Sağ kanat için eğimler; mirror ters çevirir.
        mirror(l, helm, 2.85, 5.86, -1.0, 4.8, 0.28, 0.28, 0, G_WING_L, G_WING_R, -0.62, 1, 0);
        mirror(l, helm, 2.5, 5.1, -1.0, 3.6, 0.2, 0.2, 0, G_WING_L, G_WING_R, -0.3, 1, 1);
        mirror(l, helm, 2.1, 4.3, -1.0, 2.4, 0.2, 0.2, 0, G_WING_L, G_WING_R, 0.0, 1, 1);
        mirror(l, wing, 2.7, 5.05, -1.0, 4.4, 0.9, 0.08, 0, G_WING_L, G_WING_R, -0.5, 1, 0);
        mirror(l, wing, 2.4, 4.45, -1.0, 3.6, 0.8, 0.08, 0, G_WING_L, G_WING_R, -0.3, 1, 0);
        mirror(l, wing, 2.0, 3.85, -1.0, 2.6, 0.7, 0.08, 0, G_WING_L, G_WING_R, -0.1, 1, 0);
        mirror(l, bone, 4.85, 7.1, -1.0, 0.22, 0.7, 0.22, 0, G_WING_L, G_WING_R, 0.2, 1, 1);

        // Kuyruk: arkaya doğru incelen segmentler, dikenler, ucunda kor
        for (int i = 0; i < 8; i++) {
            double w = 0.85 - 0.08 * i;
            double z = -0.9 - 0.85 * i;
            double y = 1.0 + 0.1 * i;
            int d = i < 4 ? 0 : 1;
            l.add(one(dark, 0.0, y, z, w, w * 0.9, 1.0, 0, G_TAIL, 0, 1, d));
            l.add(one(plate, 0.0, y + w * 0.9 - 0.05, z, 0.14, 0.4, 0.14, 0, G_TAIL, 0, 1, 1));
        }
        l.add(one(mag, 0.0, 1.8, -7.9, 0.3, 0.4, 0.8, 4, G_TAIL, 0, 1, 0));
        return List.copyOf(l);
    }
}
