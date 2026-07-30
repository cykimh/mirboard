package com.mirboard.domain.game.core;

/**
 * 게임별 라운드 상태 타입을 묶는 마커 인터페이스. 각 게임 도메인이 자체적으로 sealed
 * 계층(예: TichuState)을 정의하고 본 인터페이스를 확장한다.
 *
 * <p>D-98: 의도적으로 <b>메서드 0개</b>다. 라운드 번호 같은 "공통" 필드를 여기서 요구하면
 * 그 필드가 없는 게임(요트는 라운드가 아니라 12칸 기록표)이 거짓 구현을 강요당한다.
 * 중복이 실제로 보이면 그때 올린다 (`docs/game-port.md` §5 열린 질문 1).
 */
public interface GameState {
}
