package com.mirboard.domain.game.dambaduk.state;

/** 돌 종류 (룰 §4.1·§4.2). 이름은 클라와의 계약이다. 선언 순서(곧은돌 먼저)가 시간 초과 배치의 종류 순서다(룰 §8). */
public enum StoneKind {
    /** 곧은돌(둥근 돌) — 한 방향 1~2칸. */
    STRAIGHT,
    /** 꺾은돌(모난 돌) — 1칸, 또는 1칸 뒤 좌·우로 꺾어 1칸. */
    BENT
}
