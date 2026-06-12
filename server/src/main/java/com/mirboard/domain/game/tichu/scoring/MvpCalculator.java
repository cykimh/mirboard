package com.mirboard.domain.game.tichu.scoring;

import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 매치 종료 시 MVP 선정 — 승리팀 좌석 중 누적 기여도 가중합 최고. 봇은 승리팀에
 * 사람이 한 명이라도 있으면 후보에서 제외(솔로 매치에서 사람이 MVP 가 되도록).
 *
 * <p>입력 데이터는 {@link com.mirboard.domain.game.tichu.state.TichuState.RoundEnd}
 * 의 PlayerState 에서 라운드마다 추출·누적되며, 룰 엔진은 변경하지 않는다.
 */
public final class MvpCalculator {

    private MvpCalculator() {
    }

    /** 종료된 한 라운드의 좌석별 기여도(트릭/선언/완주). */
    public static List<SeatContribution> roundContributions(List<PlayerState> players) {
        return players.stream().map(MvpCalculator::contributionOf).toList();
    }

    private static SeatContribution contributionOf(PlayerState p) {
        int trick = CardPoints.sum(p.tricksWon());
        int declared = switch (p.declaration()) {
            case NONE -> 0;
            case TICHU -> CardPoints.TICHU_BONUS;
            case GRAND_TICHU -> CardPoints.GRAND_TICHU_BONUS;
        };
        boolean success = p.finishedOrder() == 1;
        int signedBonus = declared == 0 ? 0 : (success ? declared : -declared);
        int orderPoints = switch (p.finishedOrder()) {
            case 1 -> 3;
            case 2 -> 2;
            case 3 -> 1;
            default -> 0;
        };
        return new SeatContribution(p.seat(), trick, signedBonus, success ? 1 : 0, orderPoints);
    }

    /** MVP. 후보 없음(이론상 발생 안 함)이면 empty. */
    public static Optional<Mvp> select(List<SeatContribution> totals,
                                       List<Long> playerIds,
                                       Team winningTeam,
                                       Collection<Integer> botSeats) {
        List<SeatContribution> winners = totals.stream()
                .filter(c -> c.seat() >= 0 && c.seat() < playerIds.size())
                .filter(c -> Team.ofSeat(c.seat()) == winningTeam)
                .toList();
        List<SeatContribution> humans = winners.stream()
                .filter(c -> !botSeats.contains(c.seat()))
                .toList();
        List<SeatContribution> pool = humans.isEmpty() ? winners : humans;

        return pool.stream()
                .max(Comparator
                        .comparingInt(SeatContribution::weightedScore)
                        .thenComparingInt(SeatContribution::firstFinishes)
                        .thenComparingInt(SeatContribution::trickPoints)
                        .thenComparing(Comparator.comparingInt(SeatContribution::seat).reversed()))
                .map(best -> new Mvp(playerIds.get(best.seat()), best.seat(), statText(best)));
    }

    private static String statText(SeatContribution c) {
        StringBuilder sb = new StringBuilder("트릭 ").append(c.trickPoints());
        if (c.tichuBonus() != 0) {
            sb.append(" · 티츄 ").append(c.tichuBonus() > 0 ? "+" : "").append(c.tichuBonus());
        }
        if (c.firstFinishes() > 0) {
            sb.append(" · 1등 ").append(c.firstFinishes()).append("회");
        }
        return sb.toString();
    }

    /** 선정된 MVP — userId + 좌석 + 표시 문구. */
    public record Mvp(long userId, int seat, String stat) {
    }
}
