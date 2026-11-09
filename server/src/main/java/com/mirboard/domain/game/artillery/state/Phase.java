package com.mirboard.domain.game.artillery.state;

/** AIMING = 차례 주인의 액션을 기다림 · RESOLVING = 발사 연출 대기(아무의 차례도 아님, §9.2) · ENDED. */
public enum Phase {
    AIMING, RESOLVING, ENDED
}
