package com.mirboard.domain.game.onecard.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 탈락 한 건 (`docs/rules-onecard.md` §10). 상태에는 일어난 순서대로 쌓인다 — 파산자 순위가 이 순서를
 * 읽는다(늦게 파산한 쪽이 위, §11.2).
 *
 * @param cardsHeld 탈락 순간 손에 있던 장수. 손패는 뽑을 더미로 가므로 순위표용으로 따로 남긴다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Elimination(int seat, Reason reason, int cardsHeld) {

    public enum Reason {
        /** 먹은 뒤 손패 20장 이상. */
        BANKRUPT,
        /** 게임 중 나가기·끊김 유예 초과. */
        DESERTED
    }
}
