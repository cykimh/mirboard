import type { MouseEvent } from 'react';
import { roomsApi } from '@/api/rooms';
import { t } from '@/i18n/messages';
import type { Card } from '@/types/tichu';

type SendAction = (action: Record<string, unknown>) => void;

interface UseGameActionsArgs {
  roomId: string;
  token: string | null;
  sendAction: SendAction;
  /** 현재 선택된 카드 (플레이 제출 페이로드). */
  selectedCards: Card[];
  selectedCardKeys: Set<string>;
  isInPlaying: boolean;
  isInPassing: boolean;
  iAmPassSubmitted: boolean;
  clearSelection: () => void;
  toggleCardSelection: (c: Card) => void;
  selectPassCard: (c: Card) => void;
  setError: (msg: string | null) => void;
  setWishModalDismissed: (v: boolean) => void;
}

/**
 * GameTable 의 사용자 액션 핸들러 묶음. 전부 "입력을 서버 액션으로 번역" 하는 얇은
 * 함수들이고 룰 판정은 하지 않는다 — 검증은 서버(ActionValidator)가 진실 공급원.
 *
 * D-87 에서 GameTable 에서 분리. 각 핸들러 본문은 이동 전과 동일하다.
 */
export function useGameActions({
  roomId,
  token,
  sendAction,
  selectedCards,
  selectedCardKeys,
  isInPlaying,
  isInPassing,
  iAmPassSubmitted,
  clearSelection,
  toggleCardSelection,
  selectPassCard,
  setError,
  setWishModalDismissed,
}: UseGameActionsArgs) {
  function handlePlay() {
    if (selectedCards.length === 0) {
      setError(t('play.error.pickCard'));
      return;
    }
    sendAction({ '@action': 'PLAY_CARD', cards: selectedCards });
    clearSelection();
  }

  function handlePass() {
    sendAction({ '@action': 'PASS_TRICK' });
  }

  // 취소 버튼 대신 — 플레이 중 손패(.my-hand)·버튼 밖(빈 펠트/영역)을 클릭하면 선택 해제.
  function handleBackgroundClick(e: MouseEvent) {
    if (!isInPlaying || selectedCardKeys.size === 0) return;
    const target = e.target as HTMLElement;
    if (target.closest('.my-hand') || target.closest('button')) return;
    clearSelection();
  }

  function handleDeclareTichu() {
    sendAction({ '@action': 'DECLARE_TICHU' });
  }

  function handleDeclareGrandTichu() {
    sendAction({ '@action': 'DECLARE_GRAND_TICHU' });
  }

  function handleMakeWish(rank: number) {
    sendAction({ '@action': 'MAKE_WISH', rank });
    setWishModalDismissed(true);
  }

  function handleSkipWish() {
    setWishModalDismissed(true);
  }

  function handleGiveDragon(toSeat: number) {
    sendAction({ '@action': 'GIVE_DRAGON_TRICK', toSeat });
  }

  function handleReady() {
    sendAction({ '@action': 'READY' });
  }

  // D-82 — 호스트가 매치 종료 후 '한 판 더'. 새 매치 이벤트→resync→applySnapshot 이
  // matchEnded 를 정리하므로 별도 상태 처리 불필요.
  async function handleRematch() {
    if (!token) return;
    try {
      await roomsApi.rematch(token, roomId);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  function handleCardClick(c: Card) {
    if (isInPassing && !iAmPassSubmitted) {
      selectPassCard(c);
    } else if (isInPlaying) {
      toggleCardSelection(c);
    }
    // Dealing 단계에서는 카드 클릭은 의미 없음 (단지 시각 정보).
  }

  return {
    handlePlay,
    handlePass,
    handleBackgroundClick,
    handleDeclareTichu,
    handleDeclareGrandTichu,
    handleMakeWish,
    handleSkipWish,
    handleGiveDragon,
    handleReady,
    handleRematch,
    handleCardClick,
  };
}
