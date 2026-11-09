package com.mirboard.domain.game.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 게임에 따라 의미가 있기도 없기도 한 방 설정 (D-106).
 *
 * <p>모든 게임에 통하는 설정(방 이름·인원·턴 제한·봇 채우기)은 여기 없다. 여기 있는 것은
 * <b>게임 구조에 묶여 있어 어떤 게임에는 아예 뜻이 없는</b> 것들뿐이다.
 *
 * <p>새 옵션은 enum 상수 하나만 늘리면 된다 — 포트 표면
 * ({@link GameDefinition#supportedRoomOptions()})은 그대로다.
 *
 * <p><b>D-176 — 값을 가진 옵션(맵 옵션)</b>: 허용값·기본값을 상수에 같이 적은 옵션은 방의 {@code options} 맵
 * ({@code Map<RoomOption,String>}, Redis {@code room:{id}} 해시 필드 {@code opt:<NAME>})에 저장되고, 대기실
 * {@code PUT /api/rooms/{id}/settings} 의 {@code options} 로 바뀌며, 게임이 선언한 것만 {@link GameContext#options()} 로
 * 엔진에 간다. 옵션마다 {@code Room} 필드·Lua 인자를 늘리지 않으려는 것이다. 허용값이 없는 앞의 다섯은 예전처럼 자기
 * 필드(목표 점수·판돈·버튼 크기 등)에 저장된다 — 원카드 버튼 두 필드를 맵으로 옮기는 정리는 후속.
 */
public enum RoomOption {

    /** 목표 점수에 먼저 도달하면 매치 종료. 라운드 수가 고정된 게임(스컬킹 10R)에는 뜻이 없다. */
    TARGET_SCORE,

    /**
     * 이 게임에 <b>팀이 있는가</b>. 좌석 정책 UI 를 켜고 끄는 스위치가 <b>아니다</b>
     * (D-106 정정) — 좌석 정책은 게임 중립이라 개인전에서도 쓴다(좌석 순서 = 턴 순서).
     * 이 값이 가르는 것은 라벨뿐이다: 있으면 "팀 배정", 없으면 "좌석 순서".
     */
    TEAMS,

    /**
     * 방 단위 테이블 칩 내기(D-82). 정산이 팀 승패에 묶여 있어 현재는 티츄 전용이다 —
     * `RoomChipService` 가 티츄를 직접 참조하는 이유이자, 칩이 포트 밖에 있는 이유
     * (`docs/game-port.md` §2).
     */
    BETTING,

    /**
     * D-161 — 원카드 "원카드!/잡기!" 경쟁 버튼 크기(작게·보통·크게). 방 설정이라 모두 같은 크기다 — 크기가 누르기 쉬움에 영향을 주니
     * 공평하게. 화면 표시만 바꾸고 룰·엔진은 모른다.
     */
    RACE_BUTTON_SIZE,

    /**
     * D-165 — 원카드 경쟁 버튼 찾기 난이도(투명도 — 보통·어려움·매우 어려움). 크기와 같은 이유로 방 설정이다(모두 같은 값).
     */
    RACE_BUTTON_VISIBILITY,

    /**
     * D-176 — 팀전 선택(1:1 개인전 / 2:2 팀전). 게임 이름을 넣지 않았다 — "팀전을 고를 수 있는" 게임은 누구나 선언한다.
     * 정원·시작 인원은 게임이 이 값으로 정한다({@link GameDefinition#capacityFor}·{@link GameDefinition#playersToStart}).
     */
    TEAM_MODE("SOLO", "SOLO", "TEAMS_2V2"),

    /** D-176·D-177 — 오목 룰(렌주 / 삼삼만 금지 / 자유 오목). */
    OMOK_RULE("RENJU", "RENJU", "DOUBLE_THREE_ONLY", "FREESTYLE"),

    /** D-176·D-179 — 방의 봇 전원에 같은 난이도(쉬움·보통·어려움). 게임 중립 — 봇 강도를 고르는 게임은 누구나 선언한다. */
    BOT_LEVEL("NORMAL", "EASY", "NORMAL", "HARD");

    /** Redis {@code room:{id}} 해시에서 맵 옵션 필드의 접두. */
    public static final String HASH_FIELD_PREFIX = "opt:";

    private final String defaultValue;
    private final List<String> choices;

    RoomOption() {
        this.defaultValue = null;
        this.choices = List.of();
    }

    RoomOption(String defaultValue, String... values) {
        this.defaultValue = defaultValue;
        this.choices = List.of(values);
        if (!this.choices.contains(defaultValue)) {
            throw new IllegalStateException(name() + " default not in values");
        }
    }

    /** D-176 — 방의 {@code options} 맵에 저장되는 옵션인가(허용값·기본값이 있다). */
    public boolean valued() {
        return defaultValue != null;
    }

    /** 맵 옵션의 기본값 — 값을 고르지 않은 방은 이 값이다. 맵 옵션이 아니면 null. */
    public String defaultValue() {
        return defaultValue;
    }

    /** 맵 옵션의 허용값(선언 순서 — 화면 선택지 순서). 맵 옵션이 아니면 빈 목록. */
    public List<String> choices() {
        return choices;
    }

    /** 맵 옵션이고 허용값이면 true. */
    public boolean accepts(String value) {
        return value != null && valued() && choices.contains(value);
    }

    /** Redis 해시 필드 이름 — {@code opt:<NAME>}. */
    public String hashField() {
        return HASH_FIELD_PREFIX + name();
    }

    /** {@code opt:<NAME>} → 옵션. 접두가 다르거나 모르는 이름(옛 버전이 쓴 필드 등)이면 null. */
    public static RoomOption fromHashField(String field) {
        if (field == null || !field.startsWith(HASH_FIELD_PREFIX)) {
            return null;
        }
        try {
            RoomOption option = valueOf(field.substring(HASH_FIELD_PREFIX.length()));
            return option.valued() ? option : null;
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * D-176 — 게임이 선언한 맵 옵션의 실제 값. 선언한 맵 옵션마다 저장된 값이 허용값이면 그 값, 없거나 모르는 값이면 기본값을
     * 넣는다 — 그래서 선언한 옵션은 늘 값이 있고, 선언하지 않은 옵션은 저장돼 있어도 빠진다. 정원 계산·엔진 전달이 모두 이 결과를 쓴다.
     */
    public static Map<RoomOption, String> resolve(Set<RoomOption> supported, Map<RoomOption, String> stored) {
        Map<RoomOption, String> resolved = new EnumMap<>(RoomOption.class);
        for (RoomOption option : values()) {
            if (!option.valued() || !supported.contains(option)) {
                continue;
            }
            String value = stored == null ? null : stored.get(option);
            resolved.put(option, option.accepts(value) ? value : option.defaultValue);
        }
        return Collections.unmodifiableMap(resolved);
    }
}
