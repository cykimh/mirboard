package com.mirboard.domain.game.artillery.rules;

/**
 * 탄 (`docs/rules-artillery.md` §6.2). 구멍 반경 r · 피해 반경 R · 최대 피해 · 매치당 수량(−1 = 무제한, 0 = 유닛 고유 특수탄 —
 * 수량은 {@link #SPECIAL_PER_MATCH}). 홀씨탄의 수치는 갈라진 씨앗 하나의 것이다. 덩굴탄은 r 만큼 흙을 <b>쌓는다</b>(§6.2).
 */
public enum Shell {
    PEA(14, 18, 30, -1),
    PUMPKIN(24, 30, 45, 2),
    /** 덩굴탄(새싹포) — 둔덕을 쌓는다. */
    VINE(16, 18, 15, 0),
    /** 알밤탄(도토리포) — 큰 구멍, 작은 피해. */
    CHESTNUT(34, 30, 25, 0),
    /** 홀씨탄(민들레포) — 꼭대기에서 세 갈래. 수치는 씨앗 하나. */
    SPORE(10, 14, 16, 0);

    /** 유닛 고유 특수탄의 판당 수량 (D-224). */
    public static final int SPECIAL_PER_MATCH = 2;
    /** 홀씨탄이 갈라질 때 양옆 씨앗의 가로 속도 차(Q16, 0.75칸/틱). */
    public static final int SPORE_SPREAD = 49152;

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

    /** 매치당 수량, −1 = 무제한, 0 = 특수탄(유닛 고유 — {@link #SPECIAL_PER_MATCH}). */
    public int perMatch() {
        return perMatch;
    }

    public boolean special() {
        return perMatch == 0;
    }

    /** 흙을 쌓는 탄(덩굴탄)인가 — 아니면 지운다. */
    public boolean fills() {
        return this == VINE;
    }

    /** 꼭대기에서 갈라지는 탄(홀씨탄)인가. */
    public boolean splits() {
        return this == SPORE;
    }

    /** 쏜 포대에 쌓이는 딜레이 (D-226) — 콩알 100 · 호박탄 130 · 고유 특수탄 140. */
    public int delay() {
        if (this == PEA) {
            return 100;
        }
        return this == PUMPKIN ? 130 : 140;
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
