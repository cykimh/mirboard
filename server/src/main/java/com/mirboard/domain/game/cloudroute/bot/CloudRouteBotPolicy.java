package com.mirboard.domain.game.cloudroute.bot;

import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.board.Board;
import com.mirboard.domain.game.cloudroute.board.Economy;
import com.mirboard.domain.game.cloudroute.board.Tile;
import com.mirboard.domain.game.cloudroute.state.Pending;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 구름항로 휴리스틱 봇 (D-212, 설계서 `docs/plans/property-board.md` §7). 공개 정보 뷰({@link CloudRouteBotView})만 보고, 난수를 쓰지
 * 않아 같은 상태면 같은 수다.
 *
 * <p>기준은 <b>안전 잔고</b> — 판 위 남의 땅 중 지금 내가 밟으면 낼 가장 큰 통행료의 15%. 그만큼만 남기고 사고·짓고·인수한다. 섬 독점을
 * 완성하거나 남의 독점을 막는 땅은 기준을 절반으로 낮춘다.
 *
 * <p><b>왜 이렇게 적게 남기나 (S2 실측, D-212).</b> 총자산은 땅·건물을 정가로 세고(§12.2) 건물은 곧바로 통행료를 번다 — 현금을 건물로
 * 바꾸는 것은 순위상 손해가 없고, 청산(50%)을 몇 번 당해도 통행료 수입이 더 크다. 설계서 §7 의 처음 기준(max(200, 최대 통행료))은
 * "다 쓰는" 탐욕 상대에게 4인 0.17 로 졌고, 잔고를 줄일수록 강해져 15% 에서 탐욕과 대등해졌다({@code CloudRouteBotPolicyTest}).
 * 인수는 1.5배를 상대에게 주는 거래라 끝 무렵(남은 라운드 {@value #LATE_ROUNDS} 미만)에는 하지 않는다 — 그 0.5배가 그대로 상대 총자산이 된다.
 */
public final class CloudRouteBotPolicy {

    /** 안전 잔고 = 남의 최대 통행료의 이 백분율. */
    static final int RESERVE_PERCENT = 15;
    /** 남은 라운드가 이보다 적으면 인수하지 않는다. */
    static final int LATE_ROUNDS = 3;
    /** 소용돌이에서 돈을 내고 나갈 만큼 빈 땅이 남았나. */
    static final int ESCAPE_IF_UNOWNED_AT_LEAST = 4;

    private CloudRouteBotPolicy() {
    }

    /** @param legal 엔진의 합법수 — 고른 수는 늘 이 안에 있어야 한다(어댑터가 확인한다) */
    public static CloudRouteAction choose(CloudRouteBotView view, List<CloudRouteAction> legal) {
        int cash = view.myCash();
        int reserve = reserve(view);
        return switch (view.pending()) {
            case Pending.AwaitRoll __ -> new CloudRouteAction.Roll();
            case Pending.AwaitWhirlpool w -> {
                if (w.canUseBuoy()) {
                    yield new CloudRouteAction.UseBuoy();
                }
                yield w.canPay() && unownedLands(view) >= ESCAPE_IF_UNOWNED_AT_LEAST
                        ? new CloudRouteAction.PayEscape() : new CloudRouteAction.Roll();
            }
            case Pending.AwaitPurchase p -> purchase(view, p, cash, reserve);
            case Pending.AwaitBuild b -> build(view, b, cash, reserve);
            case Pending.AwaitTakeover t -> takeover(view, t, cash, reserve);
            case Pending.AwaitFestival f -> festival(view, f);
            case Pending.AwaitFlight f -> flight(view, f, cash, reserve);
        };
    }

    /** 안전 잔고 — 다음에 밟을 수 있는 가장 비싼 남의 통행료의 {@value #RESERVE_PERCENT}%. */
    static int reserve(CloudRouteBotView view) {
        int worst = 0;
        for (int t = 0; t < Board.SIZE; t++) {
            int owner = view.owners().get(t);
            if (owner >= 0 && owner != view.me()) {
                worst = Math.max(worst, tollAt(view, t));
            }
        }
        return worst * RESERVE_PERCENT / 100;
    }

    static int tollAt(CloudRouteBotView view, int tile) {
        int owner = view.owners().get(tile);
        boolean monopoly = owner >= 0 && view.owners().get(Board.partner(tile)) == owner;
        return Economy.toll(Board.tile(tile), view.levels().get(tile), monopoly, view.festivalTile() == tile);
    }

    private static CloudRouteAction purchase(CloudRouteBotView view, Pending.AwaitPurchase p, int cash, int reserve) {
        Tile tile = Board.tile(p.tile());
        int partnerOwner = view.owners().get(Board.partner(p.tile()));
        boolean strategic = partnerOwner >= 0; // 내 독점 완성 또는 남의 독점 막기
        int keep = strategic ? reserve / 2 : reserve;
        if (cash - tile.price() < keep) {
            return new CloudRouteAction.Decline();
        }
        for (int level = p.maxLevel(); level > 0; level--) {
            if (cash - tile.price() - level * tile.buildCost() >= reserve) {
                return new CloudRouteAction.Buy(level);
            }
        }
        return new CloudRouteAction.Buy(0);
    }

    private static CloudRouteAction build(CloudRouteBotView view, Pending.AwaitBuild b, int cash, int reserve) {
        Tile tile = Board.tile(b.tile());
        int current = view.levels().get(b.tile());
        for (int level = b.maxLevel(); level >= b.minLevel(); level--) {
            if (cash - (level - current) * tile.buildCost() >= reserve) {
                return new CloudRouteAction.Build(level);
            }
        }
        return new CloudRouteAction.Skip();
    }

    private static CloudRouteAction takeover(CloudRouteBotView view, Pending.AwaitTakeover t, int cash, int reserve) {
        if (view.roundLimit() - view.round() < LATE_ROUNDS || cash - t.price() < reserve) {
            return new CloudRouteAction.Skip();
        }
        int partnerOwner = view.owners().get(Board.partner(t.tile()));
        boolean completesMine = partnerOwner == view.me();
        boolean breaksTheirs = partnerOwner == t.owner();
        boolean built = view.levels().get(t.tile()) >= 1;
        return completesMine || breaksTheirs || built ? new CloudRouteAction.Takeover() : new CloudRouteAction.Skip();
    }

    private static CloudRouteAction festival(CloudRouteBotView view, Pending.AwaitFestival f) {
        int best = f.candidates().stream().max(Comparator.<Integer>comparingInt(t -> tollAt(view, t))
                .thenComparing(Comparator.reverseOrder())).orElseThrow();
        int current = view.festivalTile();
        if (current >= 0 && view.owners().get(current) == view.me()
                && tollAt(view, current) >= tollAt(view, best) * 2) {
            return new CloudRouteAction.Skip(); // 내 축제가 이미 더 큰 땅에 있다(축제 배수가 붙은 값과 비교)
        }
        return new CloudRouteAction.HostFestival(best);
    }

    private static CloudRouteAction flight(CloudRouteBotView view, Pending.AwaitFlight f, int cash, int reserve) {
        int budget = cash - Economy.FLIGHT_FEE - reserve;
        Optional<Integer> target = f.candidates().stream()
                .filter(t -> Board.isLand(t) && view.owners().get(t) < 0 && Board.tile(t).price() <= budget)
                .max(Comparator.<Integer>comparingInt(t -> Board.tile(t).price()).thenComparing(Comparator.reverseOrder()));
        return target.<CloudRouteAction>map(CloudRouteAction.Fly::new).orElse(new CloudRouteAction.Skip());
    }

    private static long unownedLands(CloudRouteBotView view) {
        return Board.tiles().stream().filter(t -> Board.isLand(t.id()) && view.owners().get(t.id()) < 0).count();
    }
}
