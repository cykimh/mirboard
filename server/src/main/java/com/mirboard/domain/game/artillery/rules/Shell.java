package com.mirboard.domain.game.artillery.rules;

/** 탄 (`docs/rules-artillery.md` §6.2). 구멍 반경 r · 피해 반경 R · 최대 피해 · 매치당 수량(−1 = 무제한). */
public enum Shell {
    PEA(14, 18, 30, -1),
    PUMPKIN(24, 30, 45, 2);

    private final int craterRadius;
    private final int blastRadius;
    private final int maxDamage;
    private final int perMatch;

    Shell(int craterRadius, int blastRadius, int maxDamage, int perMatch) {
        this.craterRadius = craterRadius;
        this.blastRadius = blastRadius;
        this.maxDamage = maxDamage;
        this.perMatch = perMatch;
    }

    public int craterRadius() {
        return craterRadius;
    }

    public int blastRadius() {
        return blastRadius;
    }

    public int maxDamage() {
        return maxDamage;
    }

    /** 매치당 수량, −1 = 무제한. */
    public int perMatch() {
        return perMatch;
    }

    /** 폭발 피해 (§6.2): 직격이면 최대, 아니면 {@code 최대 × (R − d) / R} (d < R). */
    public int damageAt(int distance, boolean direct) {
        if (direct) {
            return maxDamage;
        }
        if (distance >= blastRadius) {
            return 0;
        }
        return maxDamage * (blastRadius - distance) / blastRadius;
    }
}
