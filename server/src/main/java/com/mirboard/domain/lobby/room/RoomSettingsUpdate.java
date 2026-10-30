package com.mirboard.domain.lobby.room;

/**
 * D-161 — 대기실에서 방장이 바꾸는 게임 설정(부분 갱신 — null 은 그대로). 검증은 방을 만들 때와 같다(게임이 선언한 방 설정, D-106).
 *
 * @param turnSeconds    개인 턴 제한(초, 0 = 끔)
 * @param targetScore    목표 점수({@code TARGET_SCORE} 게임만)
 * @param stake          판돈({@code BETTING} 게임만, 허용값 {@link RoomService#ALLOWED_STAKES})
 * @param raceButtonSize 원카드 경쟁 버튼 크기({@code RACE_BUTTON_SIZE} 게임만, {@link Room#RACE_BUTTON_SIZES})
 */
public record RoomSettingsUpdate(Integer turnSeconds, Integer targetScore, Integer stake, String raceButtonSize) {
}
