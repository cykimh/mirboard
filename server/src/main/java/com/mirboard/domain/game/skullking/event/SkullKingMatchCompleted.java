package com.mirboard.domain.game.skullking.event;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-115 — 스컬킹 매치가 끝났다(10라운드 완주 또는 탈주 조기 종료). 매치 기록·게임별 전적
 * ({@code SkullKingMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link SkullKingEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저
 * 매핑({@code playerIds})과 탈주 좌석을 함께 싣는다. 인스턴스 간 전파(DomainEventBus)를 하지
 * 않는다 — 각 인스턴스가 다시 기록하면 같은 매치가 중복으로 남는다.
 *
 * @param playerIds     좌석 순서대로의 유저 id (봇 포함)
 * @param finalScores   좌석 → 최종 누적 점수
 * @param winners       승리 좌석 (공동 승리 가능, 탈주 좌석 제외 — §13-⑰⑳)
 * @param desertedSeats 탈주 확정 좌석
 * @param roundsPlayed  완주한 라운드 수 (조기 종료면 10 미만)
 */
public record SkullKingMatchCompleted(String roomId,
                                      List<Long> playerIds,
                                      Map<Integer, Integer> finalScores,
                                      List<Integer> winners,
                                      Set<Integer> desertedSeats,
                                      int roundsPlayed) {

    public SkullKingMatchCompleted {
        playerIds = List.copyOf(playerIds);
        finalScores = Map.copyOf(finalScores);
        winners = List.copyOf(winners);
        desertedSeats = Set.copyOf(desertedSeats);
    }
}
