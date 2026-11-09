package com.mirboard.domain.game.cloudroute.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.core.GameState;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 구름항로 한 판의 전체 상태 (`docs/rules-cloudroute.md`). 1판 = 1매치라 라운드·매치 상태를 나누지 않는다.
 *
 * <p>원카드({@code OneCardState})와 같은 모양이다 — 불변 레코드이고, 한 전이가 여러 필드를 함께 바꾸므로 엔진 안의 가변 사본으로
 * 고쳐 새 레코드로 얼린다. 모르는 필드는 무시한다(배포 뒤 필드를 늘렸다 되돌려도 진행 중 매치를 읽게).
 *
 * <p><b>숨기는 것(§13)</b>: {@code letterDeck} 의 순서만 서버 전용이다. 공개 뷰(S2)는 장수만 싣는다.
 *
 * @param seats            좌석별 현금·위치·소용돌이
 * @param owners           칸별 주인 좌석, 없으면 −1(땅이 아닌 칸도 −1)
 * @param levels           칸별 건설 단계 0~3
 * @param festivalTile     축제 칸, 없으면 −1
 * @param buoyHolder       구명 부표를 든 좌석, 없으면 −1
 * @param letterDeck       편지 더미, 0번이 맨 위 (서버 전용 순서)
 * @param letterDiscard    버린 더미, 놓인 순서
 * @param startSeat        시작 좌석 — 라운드 셈의 기준(§4.3)
 * @param turnSeat         차례 좌석, 끝났으면 −1
 * @param round            지금 라운드(1부터)
 * @param roundLimit       라운드 상한(시작 인원으로 정함)
 * @param doublesThisTurn  이 차례의 연속 더블 수(§4.2)
 * @param rollAgain        지금 결정이 끝나면 같은 차례에 한 번 더 굴리는가(더블)
 * @param flewThisTurn     이 차례에 이미 날았는가(§6.6)
 * @param lockedTiles      이 차례에 사거나 인수해 더 짓지 못하는 칸(§6.1·§6.2)
 * @param lastDice         마지막 주사위 두 눈, 아직 없으면 빈 목록
 * @param pending          기다리는 결정, 끝났으면 null
 * @param eliminations     탈락 순서
 * @param version          전이마다 1씩 오른다
 * @param result           끝났으면 결과, 아니면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudRouteState(List<SeatState> seats,
                              List<Integer> owners,
                              List<Integer> levels,
                              int festivalTile,
                              int buoyHolder,
                              List<Letter> letterDeck,
                              List<Letter> letterDiscard,
                              int startSeat,
                              int turnSeat,
                              int round,
                              int roundLimit,
                              int doublesThisTurn,
                              boolean rollAgain,
                              boolean flewThisTurn,
                              List<Integer> lockedTiles,
                              List<Integer> lastDice,
                              Pending pending,
                              List<Elimination> eliminations,
                              int version,
                              MatchResult result) implements GameState {

    public CloudRouteState {
        seats = List.copyOf(seats);
        owners = List.copyOf(owners);
        levels = List.copyOf(levels);
        letterDeck = List.copyOf(letterDeck);
        letterDiscard = List.copyOf(letterDiscard);
        lockedTiles = lockedTiles == null ? List.of() : List.copyOf(lockedTiles);
        lastDice = lastDice == null ? List.of() : List.copyOf(lastDice);
        eliminations = eliminations == null ? List.of() : List.copyOf(eliminations);
        if (owners.size() != Board.SIZE || levels.size() != Board.SIZE) {
            throw new IllegalArgumentException("owners/levels must have " + Board.SIZE + " tiles");
        }
    }

    public int seatCount() {
        return seats.size();
    }

    public SeatState seat(int seat) {
        return seats.get(seat);
    }

    public int cash(int seat) {
        return seats.get(seat).cash();
    }

    public boolean alive(int seat) {
        return eliminations.stream().noneMatch(e -> e.seat() == seat);
    }

    public List<Integer> aliveSeats() {
        return IntStream.range(0, seatCount()).filter(this::alive).boxed().toList();
    }

    public boolean ended() {
        return result != null;
    }

    /** 그 좌석이 가진 땅, 칸 번호 오름차순. */
    public List<Integer> ownedTiles(int seat) {
        return IntStream.range(0, Board.SIZE).filter(t -> owners.get(t) == seat).boxed().toList();
    }

    /** 총자산(§12.2) — 현금 + 가진 땅의 정가. */
    public int totalAssets(int seat) {
        int sum = cash(seat);
        for (int tile : ownedTiles(seat)) {
            sum += Economy.assetValue(Board.tile(tile), levels.get(tile));
        }
        return sum;
    }

    /** 클라 분기용 단계 이름 — 포트의 {@code phaseName} 이 그대로 쓴다. */
    public String phaseName() {
        if (result != null) {
            return "ENDED";
        }
        return switch (pending) {
            case Pending.AwaitRoll __ -> "AWAIT_ROLL";
            case Pending.AwaitWhirlpool __ -> "AWAIT_WHIRLPOOL";
            case Pending.AwaitPurchase __ -> "AWAIT_PURCHASE";
            case Pending.AwaitBuild __ -> "AWAIT_BUILD";
            case Pending.AwaitTakeover __ -> "AWAIT_TAKEOVER";
            case Pending.AwaitFestival __ -> "AWAIT_FESTIVAL";
            case Pending.AwaitFlight __ -> "AWAIT_FLIGHT";
            case Pending.AwaitGift __ -> "AWAIT_GIFT";
        };
    }
}
