package com.mirboard.domain.lobby.room;

import java.util.Map;

/**
 * D-161 — 대기실에서 방장이 바꾸는 게임 설정(부분 갱신 — null 은 그대로). 검증은 방을 만들 때와 같다(게임이 선언한 방 설정, D-106).
 *
 * @param turnSeconds    개인 턴 제한(초, 0 = 끔)
 * @param targetScore    목표 점수({@code TARGET_SCORE} 게임만)
 * @param stake          판돈({@code BETTING} 게임만, 허용값 {@link RoomService#ALLOWED_STAKES})
 * @param raceButtonSize 원카드 경쟁 버튼 크기({@code RACE_BUTTON_SIZE} 게임만, {@link Room#RACE_BUTTON_SIZES})
 * @param raceButtonVisibility 원카드 경쟁 버튼 찾기 난이도(D-165 — {@code RACE_BUTTON_VISIBILITY} 게임만,
 *                             {@link Room#RACE_BUTTON_VISIBILITIES})
 * @param options        D-176 — 값을 가진 방 옵션({@code RoomOption.valued()}) 이름 → 값. 적은 것만 바꾼다. 게임이 선언한 옵션만
 *                       받고(선언 안 한 옵션에 기본 아닌 값은 {@code UNSUPPORTED_ROOM_OPTION}), 모르는 이름·허용값 밖은
 *                       {@code INVALID_ROOM_SETTING}. 정원이 바뀌면 같이 바꾸고, 지금 인원이 새 정원보다 많으면 {@code TOO_MANY_PLAYERS}.
 */
public record RoomSettingsUpdate(Integer turnSeconds, Integer targetScore, Integer stake, String raceButtonSize,
                                 String raceButtonVisibility, Map<String, String> options) {

    public RoomSettingsUpdate {
        // 값이 null 인 항목은 다른 필드처럼 "그대로"다(부분 갱신).
        Map<String, String> kept = new java.util.LinkedHashMap<>();
        if (options != null) {
            options.forEach((name, value) -> {
                if (name != null && value != null) {
                    kept.put(name, value);
                }
            });
        }
        options = java.util.Collections.unmodifiableMap(kept);
    }

    /** D-176 이전 모양(방 옵션 맵 없음). */
    public RoomSettingsUpdate(Integer turnSeconds, Integer targetScore, Integer stake, String raceButtonSize,
                              String raceButtonVisibility) {
        this(turnSeconds, targetScore, stake, raceButtonSize, raceButtonVisibility, null);
    }

    /** D-165 이전 모양(찾기 난이도 없음). */
    public RoomSettingsUpdate(Integer turnSeconds, Integer targetScore, Integer stake, String raceButtonSize) {
        this(turnSeconds, targetScore, stake, raceButtonSize, null, null);
    }

    /** D-176 — 방 옵션만 바꾼다. */
    public static RoomSettingsUpdate ofOptions(Map<String, String> options) {
        return new RoomSettingsUpdate(null, null, null, null, null, options);
    }
}
