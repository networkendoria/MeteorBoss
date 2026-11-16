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
    static final int GROUPS = 10;
    static final String[] GROUP_NAMES = {"body", "arm_l", "arm_r", "leg_l", "leg_r", "head", "jaw", "chest", "cry_l", "cry_r"};
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
        Material obs = Material.OBSIDIAN;
        Material cry = Material.CRYING_OBSIDIAN;
        Material end = Material.END_STONE;
        Material brick = Material.END_STONE_BRICKS;
        Material ame = Material.AMETHYST_BLOCK;
        Material mag = Material.MAGMA_BLOCK;
        Material cluster = Material.AMETHYST_CLUSTER;

        // Bacaklar
        mirror(l, obs, 0.6, 0.45, 0.0, 1.0, 1.75, 1.0, 0, G_LEG_L, G_LEG_R, 0, 1, 0);
        mirror(l, obs, 0.6, 0.0, 0.25, 1.15, 0.45, 1.5, 0, G_LEG_L, G_LEG_R, 0, 1, 0);
        mirror(l, cry, 0.6, 1.0, 0.55, 0.9, 0.55, 0.25, 0, G_LEG_L, G_LEG_R, 0, 1, 1);
        mirror(l, ame, 0.6, 0.5, 0.52, 0.1, 1.5, 0.05, 1, G_LEG_L, G_LEG_R, 0, 1, 1);

        // Kalça ve karın
        l.add(one(obs, 0.0, 1.9, 0.0, 2.5, 0.5, 1.3, 0, G_BODY, 0, 1, 0));
        l.add(one(cry, 0.0, 2.4, 0.0, 2.0, 1.0, 1.1, 0, G_BODY, 0, 1, 0));
        l.add(one(ame, 0.0, 2.5, 0.56, 0.12, 0.9, 0.05, 1, G_BODY, 0, 1, 0));

        // Göğüs: X şeklinde parlayan damarlar
        l.add(one(obs, 0.0, 3.35, 0.0, 2.8, 1.35, 1.4, 0, G_CHEST, 0, 1, 0));
        l.add(one(ame, 0.0, 3.28, 0.72, 0.12, 1.5, 0.05, 1, G_CHEST, 0.62, 1, 0));
        l.add(one(ame, 0.0, 3.28, 0.72, 0.12, 1.5, 0.05, 1, G_CHEST, -0.62, 1, 0));
        l.add(one(cry, 0.0, 3.7, 0.7, 1.4, 0.7, 0.12, 0, G_CHEST, 0, 1, 1));

        // Omuz zırhı, kollar, yumruklar, parmaklar
        mirror(l, obs, 2.05, 4.2, 0.0, 1.5, 0.95, 1.7, 0, G_ARM_L, G_ARM_R, 0, 1, 0);
        mirror(l, obs, 2.3, 5.1, 0.0, 0.4, 0.7, 0.4, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        mirror(l, obs, 2.15, 1.0, 0.0, 0.95, 3.3, 0.95, 0, G_ARM_L, G_ARM_R, 0, 1, 0);
        mirror(l, ame, 2.15, 1.5, 0.5, 0.1, 2.0, 0.05, 1, G_ARM_L, G_ARM_R, 0, 1, 1);
        mirror(l, obs, 2.15, 0.1, 0.1, 1.35, 1.0, 1.3, 0, G_ARM_L, G_ARM_R, 0, 1, 0);
        for (double fx : new double[]{-0.4, 0.0, 0.4}) {
            mirror(l, obs, 2.15 + fx, 0.0, 0.65, 0.28, 0.5, 0.28, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        }

        // Kafatası, gözler, burun, boynuzlar
        l.add(one(end, 0.0, 4.7, 0.05, 1.7, 1.45, 1.6, 0, G_HEAD, 0, 1, 0));
        l.add(one(brick, 0.0, 6.05, 0.0, 1.0, 0.25, 1.0, 0, G_HEAD, 0, 1, 1));
        mirror(l, obs, 0.42, 5.3, 0.86, 0.55, 0.45, 0.06, 0, G_HEAD, G_HEAD, 0, 1, 0);
        mirror(l, ame, 0.42, 5.4, 0.9, 0.44, 0.14, 0.06, 2, G_HEAD, G_HEAD, -0.4, 1, 0);
        mirror(l, obs, 0.42, 5.62, 0.88, 0.75, 0.14, 0.12, 0, G_HEAD, G_HEAD, -0.4, 1, 0);
        l.add(one(obs, 0.0, 4.95, 0.86, 0.3, 0.35, 0.06, 0, G_HEAD, 0, 1, 0));
        mirror(l, brick, 0.75, 6.1, 0.0, 0.4, 0.7, 0.4, 0, G_HEAD, G_HEAD, 0.25, 1, 0);
        mirror(l, brick, 1.05, 6.7, 0.0, 0.28, 0.65, 0.28, 0, G_HEAD, G_HEAD, 0.55, 1, 0);

        // Çene ve dişler
        l.add(one(brick, 0.0, 4.35, 0.2, 1.3, 0.35, 1.2, 0, G_JAW, 0, 1, 0));
        for (double tx : new double[]{-0.45, -0.15, 0.15, 0.45}) {
            l.add(one(end, tx, 4.62, 0.75, 0.18, 0.3, 0.12, 0, G_JAW, 0, 1, 2));
        }

        // Omuz yanlarında süs kristalleri
        mirror(l, cluster, 2.0, 5.1, -0.35, 1.3, 2.0, 1.3, 3, G_CRY_L, G_CRY_R, 0, 1, 0);
        mirror(l, cluster, 2.55, 4.9, 0.3, 0.8, 1.2, 0.8, 3, G_CRY_L, G_CRY_R, 0.4, 1, 1);

        // Çatlaklar ve kor (evre 2 ve 3'te görünür)
        l.add(one(mag, -0.8, 3.5, 0.72, 0.45, 0.5, 0.05, 4, G_CHEST, 0.2, 2, 1));
        l.add(one(mag, 0.9, 3.85, 0.72, 0.4, 0.35, 0.05, 4, G_CHEST, -0.3, 2, 1));
        l.add(one(mag, 0.4, 2.6, 0.57, 0.35, 0.45, 0.05, 4, G_BODY, 0.2, 3, 1));
        l.add(one(mag, -2.15, 2.0, 0.49, 0.5, 0.7, 0.05, 4, G_ARM_L, 0, 2, 2));
        l.add(one(mag, 2.15, 3.0, 0.49, 0.5, 0.7, 0.05, 4, G_ARM_R, 0, 2, 2));
        mirror(l, mag, 0.6, 0.9, 0.52, 0.4, 0.6, 0.05, 4, G_LEG_L, G_LEG_R, 0, 3, 2);
        l.add(one(obs, 0.3, 5.5, 0.9, 0.08, 0.6, 0.05, 0, G_HEAD, 0.4, 2, 2));

        // ---- Ek detaylar (orta/yüksek kalitede) ----
        // Sırt: omurga dikenleri ve büyük kristal
        for (double sy0 : new double[]{2.7, 3.4, 4.1}) {
            l.add(one(obs, 0.0, sy0, -0.78, 0.3, 0.8, 0.3, 0, G_CHEST, 0, 1, 1));
        }
        l.add(one(cluster, 0.0, 3.3, -1.1, 1.5, 2.3, 1.0, 3, G_CHEST, 0, 1, 1));
        mirror(l, cluster, 1.0, 3.7, -0.9, 0.9, 1.5, 0.8, 3, G_CHEST, G_CHEST, 0.4, 1, 2);
        // Kaburgalar ve göğüs çekirdeği
        mirror(l, obs, 1.5, 3.0, 0.1, 0.15, 0.2, 1.1, 0, G_CHEST, G_CHEST, 0, 1, 2);
        mirror(l, obs, 1.5, 3.4, 0.1, 0.15, 0.2, 1.1, 0, G_CHEST, G_CHEST, 0, 1, 2);
        mirror(l, obs, 1.5, 3.8, 0.1, 0.15, 0.2, 1.1, 0, G_CHEST, G_CHEST, 0, 1, 2);
        l.add(one(ame, 0.0, 3.3, 0.74, 0.5, 0.5, 0.1, 2, G_CHEST, 0.785, 1, 0));
        // Kemer
        l.add(one(cry, 0.0, 2.3, 0.0, 2.7, 0.25, 1.45, 0, G_BODY, 0, 1, 1));
        l.add(one(ame, 0.0, 2.2, 0.74, 0.5, 0.4, 0.1, 1, G_BODY, 0, 1, 1));
        // Omuz dikenleri, kolluklar, yumruk dikenleri
        mirror(l, obs, 2.95, 4.5, 0.0, 0.8, 0.3, 0.3, 0, G_ARM_L, G_ARM_R, 0.4, 1, 2);
        mirror(l, obs, 2.9, 4.9, 0.0, 0.6, 0.25, 0.25, 0, G_ARM_L, G_ARM_R, 0.7, 1, 2);
        mirror(l, cry, 2.15, 1.2, 0.0, 1.15, 0.5, 1.15, 0, G_ARM_L, G_ARM_R, 0, 1, 1);
        mirror(l, obs, 2.15, 2.4, 0.62, 0.2, 0.2, 0.5, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        mirror(l, obs, 2.15, 3.0, 0.62, 0.2, 0.2, 0.5, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        for (double kx : new double[]{-0.4, 0.0, 0.4}) {
            mirror(l, obs, 2.15 + kx, 0.8, 0.85, 0.14, 0.14, 0.35, 0, G_ARM_L, G_ARM_R, 0, 1, 2);
        }
        // Bacak dikenleri ve pençeler
        mirror(l, obs, 1.1, 0.9, 0.0, 0.3, 0.7, 0.3, 0, G_LEG_L, G_LEG_R, 0.3, 1, 2);
        for (double cx0 : new double[]{-0.3, 0.0, 0.3}) {
            mirror(l, obs, 0.6 + cx0, 0.0, 0.95, 0.2, 0.3, 0.45, 0, G_LEG_L, G_LEG_R, 0, 1, 2);
        }
        // Kafa: taç boynuzlar, yanak boynuzları, azı dişleri
        for (double hx : new double[]{-0.45, 0.0, 0.45}) {
            l.add(one(brick, hx, 6.2, 0.0, 0.22, 0.55 + (hx == 0.0 ? 0.3 : 0.0), 0.22, 0, G_HEAD, 0, 1, 1));
        }
        mirror(l, brick, 0.95, 4.9, 0.2, 0.6, 0.25, 0.25, 0, G_HEAD, G_HEAD, 0.5, 1, 1);
        mirror(l, end, 0.4, 4.5, 0.78, 0.12, 0.28, 0.12, 0, G_HEAD, G_HEAD, 0, 1, 1);
        mirror(l, end, 0.5, 4.36, 0.7, 0.12, 0.3, 0.12, 0, G_JAW, G_JAW, 0, 1, 2);
        return List.copyOf(l);
    }
}
