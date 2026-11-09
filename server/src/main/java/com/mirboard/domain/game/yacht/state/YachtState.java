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
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record YachtState(List<Scorecard> cards,
                         int round,
                         int turnSeat,
                         TurnState turn,
                         List<Integer> deserted,
                         int actionCount,
                         MatchResult result) implements GameState {

    public static final int ROUNDS = Category.values().length;

    public YachtState {
        cards = List.copyOf(cards);
        deserted = deserted == null ? List.of() : deserted.stream().sorted().distinct().toList();
        turn = turn == null ? TurnState.none() : turn;
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
