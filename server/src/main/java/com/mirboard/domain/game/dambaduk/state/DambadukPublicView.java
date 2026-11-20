package com.mirboard.domain.game.dambaduk.state;

import java.util.List;

/**
 * 공개 뷰 (룰 §10) — 담바둑은 모든 상태가 공개다(비공개 뷰 없음). 터·점수·고를 수 있는 수는 서버가 계산해 싣는다 — 클라는 판정하지 않는다.
 *
 * @param seatColors  좌석별 색 번호(0 흑 · 1 백 · 2 셋째)
 * @param placeKinds  배치 단계에서 차례인 사람이 아직 놓을 수 있는 종류, 그 밖은 빈 목록
 * @param options     옮김 단계에서 차례인 사람이 고를 수 있는 수, 그 밖은 빈 목록
 */
public record DambadukPublicView(int seatCount,
                                 int firstSeat,
                                 List<Integer> seatColors,
                                 Phase phase,
                                 List<Stone> stones,
                                 List<Integer> walls,
                                 List<Integer> placementQueue,
                                 int turnSeat,
                                 List<Integer> jumpsLeft,
                                 List<Integer> timeouts,
                                 List<Exit> exits,
                                 int moveCount,
                                 Territory territory,
                                 List<StoneKind> placeKinds,
                                 List<MoveOption> options,
                                 MatchResult result) {

    public DambadukPublicView {
        seatColors = List.copyOf(seatColors);
        stones = List.copyOf(stones);
        walls = List.copyOf(walls);
        placementQueue = List.copyOf(placementQueue);
        jumpsLeft = List.copyOf(jumpsLeft);
        timeouts = List.copyOf(timeouts);
        exits = List.copyOf(exits);
        placeKinds = List.copyOf(placeKinds);
        options = List.copyOf(options);
    }
}
