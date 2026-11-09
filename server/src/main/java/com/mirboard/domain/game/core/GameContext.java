package com.mirboard.domain.game.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 게임 엔진 생성 시 전달되는 방 정보. per-room 엔진의 유일한 방 지식이다 — 엔진은
 * {@code Room}/{@code RoomService}(lobby 도메인)를 모른다.
 *
 * <p>D-98 에서 매치 종료 판정을 엔진으로 내리면서(D-97 판단 3) 세 필드가 추가됐다.
 * 셋 다 <b>방 설정</b>이지 게임 룰이 아니라서 여기 있다 — 게임은 이 값을 해석만 한다.
 *
 * @param targetScore 매치 종료 목표점수. 0 이면 게임이 자기 기본값을 쓴다 (티츄 1000).
 *                    점수제가 아닌 게임(스컬킹 10라운드·요트 12칸)은 무시한다.
 * @param stake       판돈(가상 칩, D-81/82). 0=내기 없음. 칩 정산 자체는 포트 밖이고
 *                    (`docs/game-port.md` §2) 매치 종료 이벤트에 실어 보내기만 한다.
 * @param botSeats    봇이 앉은 좌석. MVP 산정에서 봇을 빼는 등 게임 내부 판단에 쓴다.
 * @param options     D-176 — 이 게임이 선언한 맵 옵션({@link RoomOption#valued()})의 실제 값. 선언한 옵션은 늘 값이 있고
 *                    (고르지 않았으면 기본값), 선언하지 않은 옵션은 없다({@code GameEngineProvider.forRoom} 이
 *                    {@link RoomOption#resolve} 로 거른다). 옵션을 안 쓰는 게임은 빈 맵. 방 설정을 하나 더 실을 때(요트의 턴 제한 제안
 *                    등)는 이 레코드 끝에 붙이고 짧은 생성자를 하나 남긴다 — 지금 짧은 생성자 둘이 그 모양이다.
 */
public record GameContext(String roomId,
                          List<Long> playerIds,
                          int targetScore,
                          int stake,
                          List<Integer> botSeats,
                          Map<RoomOption, String> options) {

    public GameContext {
        playerIds = List.copyOf(playerIds);
        botSeats = botSeats == null ? List.of() : List.copyOf(botSeats);
        options = options == null || options.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new EnumMap<>(options));
    }

    /** D-176 이전 모양 — 방 옵션 없음(빈 맵). */
    public GameContext(String roomId, List<Long> playerIds, int targetScore, int stake, List<Integer> botSeats) {
        this(roomId, playerIds, targetScore, stake, botSeats, Map.of());
    }

    /** 룰 단위 테스트용 — 방 설정이 결과에 영향을 주지 않는 경로에서 쓴다. */
    public GameContext(String roomId, List<Long> playerIds) {
        this(roomId, playerIds, 0, 0, List.of(), Map.of());
    }

    /** D-176 — 맵 옵션 값. 게임이 선언하지 않은 옵션이면 null. */
    public String option(RoomOption option) {
        return options.get(option);
    }

    /** 좌석 수 = 참가자 수. 인원 가변 게임(D-99)에서 라운드 초기화가 참조한다. */
    public int seatCount() {
        return playerIds.size();
    }
}
