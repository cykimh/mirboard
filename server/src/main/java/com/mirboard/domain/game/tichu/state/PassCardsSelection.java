package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.card.Card;
import java.util.List;

/**
 * 한 플레이어가 패스 단계에서 좌/파/우 자리로 보낼 카드 3장 선택.
 * Action 의 PassCards 와 동일한 모양이지만 상태 영속화 시 액션 계층과 분리해
 * 두기 위해 별도 record 로 둔다.
 */
public record PassCardsSelection(Card toLeft, Card toPartner, Card toRight) {

    public List<Card> asList() {
        return List.of(toLeft, toPartner, toRight);
    }
}
