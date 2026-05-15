package com.mirboard.domain.lobby.room;

/**
 * Phase 8C — 4명이 모였을 때 팀 배정 정책. Tichu 는 seat 0,2 vs seat 1,3 으로
 * 팀이 갈리므로, 좌석 순서만 정하면 팀이 자동 결정됨.
 *
 * <ul>
 *   <li>{@code SEQUENTIAL} — 입장 순서대로 (현재 기본 동작).</li>
 *   <li>{@code RANDOM} — 4명 모인 직후 좌석 셔플.</li>
 *   <li>{@code MANUAL} — 호스트가 좌석을 명시 지정 (Phase 8C-extra, 본 청크에서는
 *       enum 만 예약 — 서버 동작은 SEQUENTIAL 과 동일).</li>
 * </ul>
 */
public enum TeamPolicy {
    SEQUENTIAL,
    RANDOM,
    MANUAL,
}
