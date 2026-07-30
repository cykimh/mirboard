package com.mirboard.domain.game.skullking.scoring;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 한 좌석의 한 라운드 점수 내역 (`docs/rules-skullking.md` §10, §11).
 *
 * <p>{@code base} 와 {@code bonus} 를 합치지 않고 따로 들고 있는 것은 클라 표시용이자
 * 회귀 가드다 — 명세 §11 의 "예측에 실패하면 보너스는 전부 소멸"이 지켜졌는지
 * ({@code !bidHit() ⟹ bonus == 0}) 한눈에 검사할 수 있다.
 *
 * @param bid    예측 승수
 * @param won    실제 승수
 * @param base   §10 표의 기본 점수 (실패 시 음수)
 * @param bonus  §11 보너스. 예측 실패 시 반드시 0
 */
public record RoundScore(int bid, int won, int base, int bonus) {

    public RoundScore {
        if (!hit(bid, won) && bonus != 0) {
            throw new IllegalArgumentException(
                    "예측 실패인데 보너스가 붙었다 (§11 위반): bid=" + bid + " won=" + won
                            + " bonus=" + bonus);
        }
    }

    /** 예측 적중 여부. 보너스 지급 조건이자 §10 표의 분기다. */
    @JsonIgnore
    public boolean bidHit() {
        return hit(bid, won);
    }

    public int total() {
        return base + bonus;
    }

    private static boolean hit(int bid, int won) {
        return bid == won;
    }
}
