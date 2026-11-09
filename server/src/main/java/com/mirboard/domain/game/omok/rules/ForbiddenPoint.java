package com.mirboard.domain.game.omok.rules;

/** 흑 차례의 금수 자리 하나 — 공개 뷰와 {@code TURN_CHANGED} 에 싣는다(룰 §6.1). */
public record ForbiddenPoint(int x, int y, ForbiddenKind kind) {
}
