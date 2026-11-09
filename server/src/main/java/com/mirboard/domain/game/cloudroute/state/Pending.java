package com.mirboard.domain.game.cloudroute.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * 차례 좌석이 지금 내려야 할 결정 하나 (`docs/rules-cloudroute.md` §4.1·§8). 한 번에 하나만 기다리고, 액션 하나가 이것을 푼다.
 *
 * <p>공개 정보다 — 결정 패널을 그리려고 종류·후보·가격을 그대로 공개 뷰와 {@code DECISION_REQUIRED} 이벤트에 싣는다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Pending.AwaitRoll.class, name = "ROLL"),
        @JsonSubTypes.Type(value = Pending.AwaitWhirlpool.class, name = "WHIRLPOOL"),
        @JsonSubTypes.Type(value = Pending.AwaitPurchase.class, name = "PURCHASE"),
        @JsonSubTypes.Type(value = Pending.AwaitBuild.class, name = "BUILD"),
        @JsonSubTypes.Type(value = Pending.AwaitTakeover.class, name = "TAKEOVER"),
        @JsonSubTypes.Type(value = Pending.AwaitFestival.class, name = "FESTIVAL"),
        @JsonSubTypes.Type(value = Pending.AwaitFlight.class, name = "FLIGHT"),
        @JsonSubTypes.Type(value = Pending.AwaitGift.class, name = "GIFT"),
        @JsonSubTypes.Type(value = Pending.AwaitSale.class, name = "SALE")
})
public sealed interface Pending
        permits Pending.AwaitRoll, Pending.AwaitWhirlpool, Pending.AwaitPurchase, Pending.AwaitBuild,
                Pending.AwaitTakeover, Pending.AwaitFestival, Pending.AwaitFlight, Pending.AwaitGift,
                Pending.AwaitSale {

    /** 굴리기 (§4.1). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitRoll() implements Pending {
    }

    /** 소용돌이에 갇힌 차례 (§7.2) — 굴리기·돈 내고 나가기·부표. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitWhirlpool(boolean canPay, boolean canUseBuoy) implements Pending {
    }

    /** 주인 없는 땅 사기 (§6.1). @param maxLevel 같이 지을 수 있는 최대 단계 0~2 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitPurchase(int tile, int maxLevel) implements Pending {
    }

    /** 내 땅에 짓기 (§6.2). 단계 {@code minLevel}~{@code maxLevel} 중 하나로 올린다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitBuild(int tile, int minLevel, int maxLevel) implements Pending {
    }

    /** 남의 땅 인수 (§6.4). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitTakeover(int tile, int owner, int price) implements Pending {
    }

    /** 축제 열 땅 고르기 (§6.5). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitFestival(List<Integer> candidates) implements Pending {
        public AwaitFestival {
            candidates = List.copyOf(candidates);
        }
    }

    /** 바람 정류장에서 날아갈 칸 고르기 (§6.6). @param free 정류장 무료권(D-216)이면 true — 비용 없음 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitFlight(List<Integer> candidates, boolean free) implements Pending {
        public AwaitFlight {
            candidates = List.copyOf(candidates);
        }

        public AwaitFlight(List<Integer> candidates) {
            this(candidates, false);
        }
    }

    /**
     * 수동 매각(D-217) — 낼 돈이 모자라 팔 칸을 고른다. 빚을 다 낼 때까지(또는 팔 것이 없어질 때까지) 하나씩. 빚 정보를 여기 담아 상태
     * 레코드를 늘리지 않는다 — 다 모이면 이 값으로 원래 지불(통행료·은행)을 마저 한다.
     *
     * @param debt       낼 돈
     * @param creditor   받을 좌석(은행이면 −1)
     * @param reason     {@code TOLL} 또는 은행 사유({@code CUSTOMS}·{@code LETTER})
     * @param tile       통행료면 그 칸, 아니면 −1
     * @param candidates 팔 수 있는 내 땅(그 칸의 다음 단위 — 건물이 있으면 한 단계, 없으면 땅)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitSale(int debt, int creditor, String reason, int tile, boolean monopoly, boolean festival,
                     List<Integer> candidates) implements Pending {
        public AwaitSale {
            candidates = List.copyOf(candidates);
        }
    }

    /** 등불 선물(D-216) — 1단계 무료로 올릴 내 땅 고르기. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwaitGift(List<Integer> candidates) implements Pending {
        public AwaitGift {
            candidates = List.copyOf(candidates);
        }
    }
}
