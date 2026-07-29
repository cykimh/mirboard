import { useMemo, useState } from 'react';
import { t } from '@/i18n/messages';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { comboLabel, isSelectionPlayable } from './handType';
import { sortedHand, useTichuStore } from './tichuStore';

interface UseGameTableModelArgs {
  playerIds: number[];
  myUserId: number;
}

/**
 * 게임판 뷰 모델 — tichuStore 구독과 거기서 나오는 파생값을 한곳에 모은다.
 *
 * GameTable 이 조립 루트로만 남게 하려고 D-87 에서 추가했다. 여기서 나가는 값은
 * 전부 store + props 의 함수이고 부수효과는 없다(부수효과는 useGameTableEffects).
 * 유일한 예외가 소원 모달 dismiss 플래그인데, 모달 표시 여부(showWishModal)를
 * 계산하려면 같은 자리에 있어야 해서 함께 둔다.
 *
 * phaseLabel/arenaTint 는 tableView 가 null 이면 의미가 없다 — 호출부가 로딩
 * 분기에서 걸러내므로 그때 값은 쓰이지 않는다.
 */
export function useGameTableModel({ playerIds, myUserId }: UseGameTableModelArgs) {
  const tableView = useTichuStore((s) => s.tableView);
  const privateHand = useTichuStore((s) => s.privateHand);
  const selectedCardKeys = useTichuStore((s) => s.selectedCardKeys);
  const toggleCardSelection = useTichuStore((s) => s.toggleCardSelection);
  const clearSelection = useTichuStore((s) => s.clearSelection);
  const passSelection = useTichuStore((s) => s.passSelection);
  const pendingPassCardKey = useTichuStore((s) => s.pendingPassCardKey);
  const selectPassCard = useTichuStore((s) => s.selectPassCard);
  const assignPassSlot = useTichuStore((s) => s.assignPassSlot);
  const clearPassSelection = useTichuStore((s) => s.clearPassSelection);
  const reorderHand = useTichuStore((s) => s.reorderHand);
  const orderedHand = useTichuStore(sortedHand);
  const errorMessage = useTichuStore((s) => s.errorMessage);
  const roundEnded = useTichuStore((s) => s.roundEnded);
  const matchEnded = useTichuStore((s) => s.matchEnded);
  const roundHistory = useTichuStore((s) => s.roundHistory);
  const disconnectedSeats = useTichuStore((s) => s.disconnectedSeats);
  const chips = useTichuStore((s) => s.chips);
  const chipDeltas = useTichuStore((s) => s.chipDeltas);
  const lastReceived = useTichuStore((s) => s.lastReceived);
  const clearReceived = useTichuStore((s) => s.clearReceived);
  const setError = useTichuStore((s) => s.setError);
  const setRoundEnded = useTichuStore((s) => s.setRoundEnded);

  const mySeat = playerIds.indexOf(myUserId);
  const myTeam: 'A' | 'B' = mySeat >= 0 && mySeat % 2 === 1 ? 'B' : 'A';
  const [wishModalDismissed, setWishModalDismissed] = useState(false);

  const phase = tableView?.phase ?? null;
  const dealingCardCount = tableView?.dealingCardCount ?? 0;
  const isInDealing = phase === 'DEALING';
  const isInPassing = phase === 'PASSING';
  const isInPlaying = phase === 'PLAYING';
  const iAmReady = isInDealing && (tableView?.readySeats ?? []).includes(mySeat);
  const iAmPassSubmitted =
    isInPassing && (tableView?.passingSubmittedSeats ?? []).includes(mySeat);
  const myDeclaration = tableView?.declarations?.[mySeat] ?? 'NONE';
  const myTurn = isInPlaying && tableView !== null && tableView.currentTurnSeat === mySeat;

  const myMahjongLeadActive =
    isInPlaying &&
    tableView !== null &&
    tableView.currentTopSeat === mySeat &&
    tableView.currentTop !== null &&
    tableView.currentTop.cards.length === 1 &&
    tableView.currentTop.cards[0].special === 'MAHJONG' &&
    tableView.activeWishRank === null;

  const wishContextKey = myMahjongLeadActive
    ? `${tableView.currentTopSeat}-mahjong`
    : null;

  const showWishModal = myMahjongLeadActive && !wishModalDismissed;

  // Dragon 양도 강제 상태: Dragon 단독으로 내가 받았고, 서버가 TrickTaken 대신
  // TurnChanged(taker=mySeat) 만 발행해서 currentTurnSeat 가 다시 본인.
  const mustGiveDragon =
    isInPlaying &&
    tableView !== null &&
    tableView.currentTopSeat === mySeat &&
    tableView.currentTurnSeat === mySeat &&
    tableView.currentTop !== null &&
    tableView.currentTop.cards.length === 1 &&
    tableView.currentTop.cards[0].special === 'DRAGON';

  const selectedCards = useMemo<Card[]>(() => {
    if (!privateHand) return [];
    return privateHand.cards.filter((c) => selectedCardKeys.has(cardKey(c)));
  }, [privateHand, selectedCardKeys]);

  // Phase 12C/#2 — 선택 카드 조합명 ("페어2" 형식, 표시용 hint. 서버가 실제 검증).
  const selectedCombo = useMemo(
    () => comboLabel(selectedCards),
    [selectedCards],
  );

  // 선택한 패를 "지금 진짜로 낼 수 있는지" — 서버 HandComparator 미러로 좁게 판정.
  // 확실히 불가일 때만 false → 내기 버튼 비활성(거짓 비활성 회피, 위시는 서버 판정).
  const selectedPlayable = useMemo(
    () => isInPlaying && isSelectionPlayable(selectedCards, tableView?.currentTop ?? null),
    [isInPlaying, selectedCards, tableView?.currentTop],
  );

  const passCardsBySlot = useMemo(() => {
    if (!privateHand) return { left: null, partner: null, right: null };
    const findByKey = (key: string | null): Card | null =>
      key ? privateHand.cards.find((c) => cardKey(c) === key) ?? null : null;
    return {
      left: findByKey(passSelection.left),
      partner: findByKey(passSelection.partner),
      right: findByKey(passSelection.right),
    };
  }, [privateHand, passSelection]);

  // Phase 15(#2) — 줄 사람에게 배정된 카드는 손패에서 제거(시각적으로도 사라짐).
  const assignedPassKeys = useMemo(
    () => new Set(Object.values(passSelection).filter((v): v is string => v !== null)),
    [passSelection],
  );
  const handCards = useMemo(
    () =>
      isInPassing && !iAmPassSubmitted
        ? orderedHand.filter((c) => !assignedPassKeys.has(cardKey(c)))
        : orderedHand,
    [orderedHand, isInPassing, iAmPassSubmitted, assignedPassKeys],
  );

  const phaseLabel =
    phase === 'DEALING'
      ? `${t('game.phase.dealing')} (${dealingCardCount}${t('seat.handCardsSuffix')})`
      : phase === 'PASSING'
      ? t('game.phase.passing')
      : phase === 'PLAYING'
      ? t('game.phase.playing')
      : t('game.phase.roundEnd');

  // P3(8) — 티츄 선언 시 경기장 틴트: 그랜드=빨강 우선, 일반=파랑(라운드 동안 지속).
  const declValues = Object.values(tableView?.declarations ?? {});
  const arenaTint = declValues.includes('GRAND_TICHU')
    ? 'arena-tint-grand'
    : declValues.includes('TICHU')
    ? 'arena-tint-tichu'
    : '';

  return {
    // 스토어 값
    tableView,
    privateHand,
    selectedCardKeys,
    passSelection,
    pendingPassCardKey,
    errorMessage,
    roundEnded,
    matchEnded,
    roundHistory,
    disconnectedSeats,
    chips,
    chipDeltas,
    lastReceived,
    // 스토어 액션
    toggleCardSelection,
    clearSelection,
    selectPassCard,
    assignPassSlot,
    clearPassSelection,
    reorderHand,
    clearReceived,
    setError,
    setRoundEnded,
    // 파생
    mySeat,
    myTeam,
    phase,
    dealingCardCount,
    isInDealing,
    isInPassing,
    isInPlaying,
    iAmReady,
    iAmPassSubmitted,
    myDeclaration,
    myTurn,
    wishContextKey,
    showWishModal,
    setWishModalDismissed,
    mustGiveDragon,
    selectedCards,
    selectedCombo,
    selectedPlayable,
    passCardsBySlot,
    handCards,
    phaseLabel,
    arenaTint,
  };
}
