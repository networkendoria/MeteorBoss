package com.zyven.meteorboss;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.zyven.meteorboss.TyphonModel.*;

/** Parçaların pivot ve dönüş hesabı (saf matematik, sunucuya bağımlı değil). */
final class BossPose {

    private BossPose() {}

    private static final Vector3f PIVOT_ARM_L = new Vector3f(-2.15f, 4.3f, 0f);
    private static final Vector3f PIVOT_ARM_R = new Vector3f(2.15f, 4.3f, 0f);
    private static final Vector3f PIVOT_LEG_L = new Vector3f(-0.6f, 2.2f, 0f);
    private static final Vector3f PIVOT_LEG_R = new Vector3f(0.6f, 2.2f, 0f);
    private static final Vector3f PIVOT_HEAD = new Vector3f(0f, 4.6f, 0f);
    private static final Vector3f PIVOT_WING_L = new Vector3f(-0.7f, 4.6f, -0.9f);
    private static final Vector3f PIVOT_WING_R = new Vector3f(0.7f, 4.6f, -0.9f);
    private static final Vector3f PIVOT_TAIL = new Vector3f(0f, 1.5f, -0.6f);

    private static Vector3f pivotOf(int group) {
        return switch (group) {
            case G_ARM_L -> PIVOT_ARM_L;
            case G_ARM_R -> PIVOT_ARM_R;
            case G_LEG_L -> PIVOT_LEG_L;
            case G_LEG_R -> PIVOT_LEG_R;
            case G_HEAD, G_JAW -> PIVOT_HEAD;
            case G_WING_L -> PIVOT_WING_L;
            case G_WING_R -> PIVOT_WING_R;
            case G_TAIL -> PIVOT_TAIL;
            default -> null;
        };
    }

    private static double angleOf(BossState b, int group) {
        return switch (group) {
            case G_ARM_L -> b.aArmL;
            case G_ARM_R -> b.aArmR;
            case G_LEG_L -> b.aLegL;
            case G_LEG_R -> b.aLegR;
            case G_HEAD -> b.aHead;
            case G_JAW -> b.aHead + b.aJaw;
            case G_WING_L -> -b.aWing;
            case G_WING_R -> b.aWing;
            case G_TAIL -> b.aTail;
            default -> 0.0;
        };
    }

    /** Gruba göre dönüş ekseni: kanatlar Z (çırpma), kuyruk Y (sallanma), diğerleri X. */
    private static Quaternionf rot(int group, double angle) {
        Quaternionf q = new Quaternionf();
        if (group == G_WING_L || group == G_WING_R) {
            return q.rotateZ((float) angle);
        }
        if (group == G_TAIL) {
            return q.rotateY((float) angle);
        }
        return q.rotateX((float) angle);
    }

    /** Bir parçanın o anki duruşa göre dönüşümü. k: büyüme (çıkış sahnesinde 0..1). */
    private static Transformation buildTransform(Part p, BossState b, double k) {
        boolean hidden = b.phase < p.minPhase();
        double sy = p.sy();
        double bottom = p.cy();
        if (p.group() == G_CHEST) {
            sy = p.sy() * (1.0 + b.breath);
        }
        if (p.group() == G_CRY_L) {
            bottom += b.bobL;
        } else if (p.group() == G_CRY_R) {
            bottom += b.bobR;
        }

        Vector3f half = new Vector3f((float) (p.sx() / 2), (float) (sy / 2), (float) (p.sz() / 2));
        Vector3f center = new Vector3f((float) p.cx(), (float) (bottom + sy / 2), (float) p.cz());
        Quaternionf q0 = new Quaternionf();
        if (p.roll() != 0) {
            q0.rotateZ((float) p.roll());
        }
        Vector3f t = new Vector3f(center).sub(q0.transform(new Vector3f(half)));
        Quaternionf q = q0;

        Vector3f pivot = pivotOf(p.group());
        double angle = angleOf(b, p.group());
        if (pivot != null && Math.abs(angle) > 1e-4) {
            Quaternionf qp = rot(p.group(), angle);
            t.sub(pivot);
            qp.transform(t);
            t.add(pivot);
            q = new Quaternionf(qp).mul(q0);
        }

        if (hidden) {
            return new Transformation(new Vector3f(center).mul((float) k), new Quaternionf(),
                    new Vector3f(0.001f, 0.001f, 0.001f), new Quaternionf());
        }
        float kk = (float) k;
        return new Transformation(t.mul(kk), q,
                new Vector3f((float) p.sx() * kk, (float) sy * kk, (float) p.sz() * kk), new Quaternionf());
    }

    static Transformation transform(Part p, BossState b, double k) {
        return buildTransform(p, b, k);
    }
}
