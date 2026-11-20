package com.mirboard.domain.game.dambaduk.state;

/** 빠진 좌석 하나 — 빠진 순서대로 상태에 쌓인다. */
public record Exit(int seat, ExitReason reason) {
}
