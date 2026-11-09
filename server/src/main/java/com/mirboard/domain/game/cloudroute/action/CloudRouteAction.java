package com.mirboard.domain.game.cloudroute.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;

/**
 * 구름항로 액션 sealed 계층 (`docs/rules-cloudroute.md` §8). 클라가 보내는 것은 <b>선택</b>뿐이다 — 주사위·가격·통행료는 서버가
 * 정하므로 어떤 액션에도 실리지 않는다(Server-Authoritative).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CloudRouteAction.Roll.class, name = "ROLL"),
        @JsonSubTypes.Type(value = CloudRouteAction.PayEscape.class, name = "PAY_ESCAPE"),
        @JsonSubTypes.Type(value = CloudRouteAction.UseBuoy.class, name = "USE_BUOY"),
        @JsonSubTypes.Type(value = CloudRouteAction.Buy.class, name = "BUY"),
        @JsonSubTypes.Type(value = CloudRouteAction.Decline.class, name = "DECLINE"),
        @JsonSubTypes.Type(value = CloudRouteAction.Build.class, name = "BUILD"),
        @JsonSubTypes.Type(value = CloudRouteAction.Skip.class, name = "SKIP"),
        @JsonSubTypes.Type(value = CloudRouteAction.Takeover.class, name = "TAKEOVER"),
        @JsonSubTypes.Type(value = CloudRouteAction.HostFestival.class, name = "HOST_FESTIVAL"),
        @JsonSubTypes.Type(value = CloudRouteAction.Fly.class, name = "FLY"),
        @JsonSubTypes.Type(value = CloudRouteAction.GiftBuild.class, name = "GIFT_BUILD"),
        @JsonSubTypes.Type(value = CloudRouteAction.Sell.class, name = "SELL")
})
public sealed interface CloudRouteAction extends GameAction
        permits CloudRouteAction.Roll, CloudRouteAction.PayEscape, CloudRouteAction.UseBuoy, CloudRouteAction.Buy,
                CloudRouteAction.Decline, CloudRouteAction.Build, CloudRouteAction.Skip, CloudRouteAction.Takeover,
                CloudRouteAction.HostFestival, CloudRouteAction.Fly, CloudRouteAction.GiftBuild,
                CloudRouteAction.Sell {

    /** 굴리기 — 보통 차례와 소용돌이 차례 모두(§4.1·§7.2). */
    record Roll() implements CloudRouteAction {
    }

    /** 50G 내고 소용돌이에서 나가기(§7.2). */
    record PayEscape() implements CloudRouteAction {
    }

    /** 구명 부표로 소용돌이에서 나가기(§7.2). */
    record UseBuoy() implements CloudRouteAction {
    }

    /** 땅 사기 + 그 자리에서 {@code buildTo} 단계까지 짓기(0~2, §6.1). */
    record Buy(int buildTo) implements CloudRouteAction {
    }

    /** 땅 안 사기(§6.1). */
    record Decline() implements CloudRouteAction {
    }

    /** 내 땅을 {@code buildTo} 단계까지 올리기(§6.2). */
    record Build(int buildTo) implements CloudRouteAction {
    }

    /** 건설·인수·축제·비행 건너뛰기. */
    record Skip() implements CloudRouteAction {
    }

    /** 인수(§6.4). */
    record Takeover() implements CloudRouteAction {
    }

    /** 축제 열기(§6.5). */
    record HostFestival(int tile) implements CloudRouteAction {
    }

    /** 바람 정류장에서 날기(§6.6). */
    record Fly(int tile) implements CloudRouteAction {
    }

    /** 수동 매각(D-217) — 그 칸의 다음 단위(건물 한 단계, 없으면 땅)를 반값에 판다. */
    record Sell(int tile) implements CloudRouteAction {
    }

    /** 등불 선물로 1단계 무료 건설(D-216). */
    record GiftBuild(int tile) implements CloudRouteAction {
    }
}
