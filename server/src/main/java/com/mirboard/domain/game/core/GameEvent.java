package com.mirboard.domain.game.core;

/**
 * 게임이 발행하는 이벤트를 묶는 마커 인터페이스. 각 게임 도메인이 자체적으로 sealed
 * 계층(예: TichuEvent)을 정의하고 본 인터페이스를 확장한다.
 */
public interface GameEvent {
}
