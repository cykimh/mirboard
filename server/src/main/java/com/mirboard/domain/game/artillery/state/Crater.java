package com.mirboard.domain.game.artillery.state;

/** 지형 연산 하나 — 원 지우기 (§6.2). 상태는 이 목록과 시드만으로 지형을 다시 만든다(D-220). */
public record Crater(int x, int y, int r) {
}
