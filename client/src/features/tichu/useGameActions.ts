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
  /** 아직 보내지 않은, 소원 모달 대기 중인 플레이. null 이면 모달은 닫혀 있다. */
  pendingWishPlay: Card[] | null;
  setPendingWishPlay: (cards: Card[] | null) => void;
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
  pendingWishPlay,
  setPendingWishPlay,
}: UseGameActionsArgs) {
  function handlePlay() {
    if (selectedCards.length === 0) {
      setError(t('play.error.pickCard'));
      return;
    }
    // D-108 — 마작이 포함되면 소원을 먼저 묻고 한 프레임으로 보낸다. 여기서는 아직
    // 전송하지 않는다. 소원은 마작을 내는 행위의 일부라 서버도 한 액션으로 받는다.
    if (selectedCards.some((c) => c.special === 'MAHJONG')) {
      setPendingWishPlay(selectedCards);
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

  /** 소원을 지정하고 낸다 — PLAY_CARD 한 프레임에 wishRank 를 동봉. */
  function handleConfirmWishPlay(rank: number) {
    if (!pendingWishPlay) return;
    sendAction({ '@action': 'PLAY_CARD', cards: pendingWishPlay, wishRank: rank });
    setPendingWishPlay(null);
    clearSelection();
  }

  /** 소원 없이 낸다 — 일반 PLAY_CARD 와 동일한 프레임. */
  function handlePlayWithoutWish() {
    if (!pendingWishPlay) return;
    sendAction({ '@action': 'PLAY_CARD', cards: pendingWishPlay });
    setPendingWishPlay(null);
    clearSelection();
  }

  /** 취소 — 전송하지 않고 선택도 유지한다(다시 "내기" 를 누를 수 있게). */
  function handleCancelWishPlay() {
    setPendingWishPlay(null);
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
    handleConfirmWishPlay,
    handlePlayWithoutWish,
    handleCancelWishPlay,
    handleGiveDragon,
    handleReady,
    handleRematch,
    handleCardClick,
  };
}
