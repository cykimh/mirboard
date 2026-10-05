package com.mirboard.domain.game.onecard.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 원카드 한 판의 전체 상태 (`docs/rules-onecard.md`). 1판 = 1매치라 라운드·매치 상태를 나누지 않는다.
 *
 * <p>단계(진행·경쟁·종료)를 sealed 하위 타입으로 나누지 않은 것은 의도적이다 — 스컬킹은 단계마다 들고
 * 있는 것이 달랐지만, 원카드는 세 단계가 같은 테이블(손패·더미·차례)을 공유하고 경쟁 창과 결과만
 * 붙었다 떨어진다. 그래서 {@code race}·{@code result} 를 nullable 로 두고 단계는 파생한다.
 *
 * <p>Redis 에 JSON 으로 저장된다(D-128). 모르는 필드는 무시한다 — 배포 뒤 필드를 늘렸다가 되돌려도 진행 중
 * 매치를 읽을 수 있게 하려는 것으로, 스컬킹 매치 상태(D-120)와 같은 이유다. 상태 레코드 6종 — 이 레코드와 안에
 * 든 {@link RaceWindow}·{@link RaceWindow.BotPress}·{@link Elimination}·{@link MatchResult}·
 * {@link MatchResult.Standing} — 이 모두 그렇다. 카드({@code PlayingCard})와 enum 은 해당하지 않는다 — 롤백
 * 뒤에도 읽히려면 그 모양(카드 필드·enum 상수)은 바뀌지 않아야 한다.
 *
 * @param hands        좌석별 손패. 탈락자는 빈 목록
 * @param drawPile     뽑을 더미, 0번이 맨 위
 * @param discardPile  버린 더미, 마지막이 맨 위
 * @param turnSeat     차례인 좌석. 경쟁 창이 열렸거나 끝났으면 −1
 * @param direction    +1 이면 좌석 번호가 커지는 쪽
 * @param declaredSuit 맨 위가 7 일 때 지정된 무늬, 아니면 null
 * @param attackStack  누적 공격 장수. 0 이면 공격 없음
 * @param race         열린 경쟁 창, 없으면 null
 * @param eliminations 탈락 순서
 * @param passStreak   연속 패스 수(§11.3)
 * @param turnCount    내기·먹기 횟수(§11.3 차례 상한)
 * @param version      전이마다 오른다(단조 증가, 탈락이 낀 전이는 +2) — 비공개 손패 이벤트의 {@code handVersion}·창 번호
 * @param result       끝났으면 결과, 아니면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneCardState(List<List<PlayingCard>> hands,
                           List<PlayingCard> drawPile,
                           List<PlayingCard> discardPile,
                           int turnSeat,
                           int direction,
                           Suit declaredSuit,
                           int attackStack,
                           RaceWindow race,
                           List<Elimination> eliminations,
                           int passStreak,
                           int turnCount,
                           int version,
                           MatchResult result) implements GameState {

    public OneCardState {
        hands = hands.stream().<List<PlayingCard>>map(List::copyOf).toList();
        drawPile = List.copyOf(drawPile);
        discardPile = List.copyOf(discardPile);
        eliminations = eliminations == null ? List.of() : List.copyOf(eliminations);
    }

    public int seatCount() {
        return hands.size();
    }

    public PlayingCard topCard() {
        return discardPile.get(discardPile.size() - 1);
    }

    /** 기준 무늬(§5.1) — 7 로 지정된 무늬, 없으면 맨 위 카드의 무늬. 맨 위가 조커면 null. */
    public Suit baseSuit() {
        return declaredSuit != null ? declaredSuit : topCard().suit();
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

    /** 클라 분기용 단계 이름 — 포트의 {@code phaseName} 이 그대로 쓴다. */
    public String phaseName() {
        if (result != null) {
            return "ENDED";
        }
        return race != null ? "RACE" : "PLAYING";
    }
}
