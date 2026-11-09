package com.mirboard.domain.game.cloudroute.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 탈락 한 건 (`docs/rules-cloudroute.md` §11). 상태에는 일어난 순서대로 쌓인다 — 파산자 순위가 이 순서를 읽는다(§12.2). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Elimination(int seat, Reason reason) {

    public enum Reason {
        /** 내야 할 돈을 청산해도 다 못 냈다(§10). */
        BANKRUPT,
        /** 게임 중 나가기·끊김 유예 초과. */
        DESERTED
    }
}
