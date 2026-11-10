package com.mirboard.domain.game.artillery.bot;

import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Unit;

/** 테스트 전용 — 패키지 밖 테스트가 봇의 표적 고르기를 본다. */
public final class ArtilleryBotPolicyAccess {

    private ArtilleryBotPolicyAccess() {
    }

    public static Unit target(ArtilleryState state, int seat) {
        return ArtilleryBotPolicy.pickTarget(state, seat);
    }
}
