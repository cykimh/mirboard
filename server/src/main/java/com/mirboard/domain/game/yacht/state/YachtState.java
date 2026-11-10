package com.mirboard.domain.game.yacht.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 요트 한 판의 전체 상태 (`docs/rules-yacht.md`). 1판 = 1매치, 12라운드는 이 안의 숫자다.
 *
 * <p>숨길 정보가 없다 — 공개 뷰가 상태의 전부를 보인다. 그래서 <b>미래 눈·시드·난수기 상태를 여기 두지 않는다</b>(§1,
 * D-182): 눈은 굴리는 순간 주입된 {@code DiceRoller} 가 정한다.
 *
 * <p>Redis 에 JSON 으로 저장된다(S3). 모르는 필드는 무시한다 — 원카드 상태(D-128)와 같은 이유.
 *
 * @param cards       좌석별 점수표
 * @param round       라운드 1~12. 끝난 뒤에도 마지막 값 그대로
 * @param turnSeat    차례 좌석, 끝났으면 −1
 * @param turn        지금 차례의 주사위
 * @param deserted    탈주한 좌석(오름차순)
 * @param actionCount 성공한 액션 수(§8.1 상한)
 * @param result      끝났으면 결과, 아니면 null
 * @param simultaneous D-202 — 동시 진행(라운드마다 모두 같이 굴림). 이때 {@code turnSeat} 는 −1, {@code turn} 은 주사위 없이 <b>라운드
 *                    시계</b>(시작 시각만 — 차례 전체 제한의 기준)이고, 좌석별 주사위는 {@code seatTurns} 에 있다
 * @param seatTurns   D-202 — 동시 진행의 좌석별 주사위(좌석 수만큼). 이번 라운드를 기록한 좌석·탈주 좌석은 빈 주사위. 차례대로면 빈 목록
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record YachtState(List<Scorecard> cards,
                         int round,
                         int turnSeat,
                         TurnState turn,
                         List<Integer> deserted,
                         int actionCount,
                         MatchResult result,
                         boolean simultaneous,
                         List<TurnState> seatTurns) implements GameState {

    public static final int ROUNDS = Category.values().length;

    public YachtState {
        cards = List.copyOf(cards);
        deserted = deserted == null ? List.of() : deserted.stream().sorted().distinct().toList();
        turn = turn == null ? TurnState.none() : turn;
        // 옛 JSON(D-202 이전)은 차례대로다.
        seatTurns = seatTurns == null ? List.of() : List.copyOf(seatTurns);
    }

    /** 차례대로 진행 상태(D-202 이전 모양). */
    public YachtState(List<Scorecard> cards, int round, int turnSeat, TurnState turn, List<Integer> deserted,
                      int actionCount, MatchResult result) {
        this(cards, round, turnSeat, turn, deserted, actionCount, result, false, List.of());
    }

    /** 이 좌석의 지금 주사위. 차례대로면 차례인 좌석만 실제 값, 나머지는 빈 주사위. */
    public TurnState turnOf(int seat) {
        if (simultaneous) {
            return seat >= 0 && seat < seatTurns.size() ? seatTurns.get(seat) : TurnState.none();
        }
        return seat == turnSeat ? turn : TurnState.none();
    }

    /** 이 좌석이 지금 둘 차례인가 — 동시 진행이면 이번 라운드를 아직 기록하지 않은 살아 있는 좌석 모두. */
    public boolean pending(int seat) {
        if (ended() || seat < 0 || seat >= seatCount() || !alive(seat)) {
            return false;
        }
        return simultaneous ? card(seat).filledCount() < round : seat == turnSeat;
    }

    /** 지금 둘 수 있는 좌석(오름차순). */
    public List<Integer> pendingSeats() {
        return IntStream.range(0, seatCount()).filter(this::pending).boxed().toList();
    }

    public YachtState withCards(List<Scorecard> nextCards) {
        return new YachtState(nextCards, round, turnSeat, turn, deserted, actionCount, result, simultaneous, seatTurns);
    }

    public YachtState withActionCount(int count) {
        return new YachtState(cards, round, turnSeat, turn, deserted, count, result, simultaneous, seatTurns);
    }

    /** 이 좌석의 주사위를 바꾼다 — 차례대로면 {@code turn}, 동시 진행이면 {@code seatTurns[seat]}. */
    public YachtState withTurnOf(int seat, TurnState next) {
        if (!simultaneous) {
            return new YachtState(cards, round, turnSeat, next, deserted, actionCount, result, false, seatTurns);
        }
        List<TurnState> turns = new java.util.ArrayList<>(seatTurns);
        turns.set(seat, next);
        return new YachtState(cards, round, turnSeat, turn, deserted, actionCount, result, true, turns);
    }

    public int seatCount() {
        return cards.size();
    }

    public Scorecard card(int seat) {
        return cards.get(seat);
    }

    public boolean alive(int seat) {
        return !deserted.contains(seat);
    }

    public List<Integer> aliveSeats() {
        return IntStream.range(0, seatCount()).filter(this::alive).boxed().toList();
    }

    public boolean ended() {
        return result != null;
    }

    /** 액션 수 상한 — 좌석 수 × 12 × 40 (§8.1). */
    public int actionLimit() {
        return seatCount() * ROUNDS * 40;
    }

    /** 클라 분기용 단계 이름 — 포트의 {@code phaseName}. */
    public String phaseName() {
        return result != null ? "ENDED" : "PLAYING";
    }
}
