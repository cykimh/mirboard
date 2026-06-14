import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import {
  useTichuStore,
  sortedHand,
  type PassSlot,
} from '@/features/tichu/tichuStore';
import { useStompRoom } from '@/ws/useStompRoom';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { t } from '@/i18n/messages';
import { comboLabel } from './handType';
import { CardChip } from './CardChip';
import { SortableHand } from './SortableHand';
import { SeatAvatar } from './SeatAvatar';
import { SeatCardStack } from './SeatCardStack';
import { MakeWishModal } from './MakeWishModal';
import { GiveDragonTrickModal, opponentSeatsOf } from './GiveDragonTrickModal';
import { EffectsOverlay } from './EffectsOverlay';
import { useEffectStore } from './effectStore';
import { useSfx } from './useSfx';
import { useCardAnimStore } from './cardAnimStore';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { ArenaChatBubbles } from '@/features/chat/ArenaChatBubbles';
import { ReactionFloats } from './ReactionFloats';
import { useRoomChatStore } from '@/features/chat/roomChatStore';

interface GameTableProps {
  roomId: string;
  playerIds: number[];
  myUserId: number;
  /** true 이면 관전자 모드: 손패/액션 버튼/모달 미표시. TableView 만 시청. */
  spectator?: boolean;
  /** Phase 9D — playerIds 인덱스 중 봇이 차지한 좌석. SeatAvatar 가 🤖 표시. */
  botSeats?: number[];
  /** Phase 9D — 솔로 모드 (방 생성 시 봇 자동 채움). 헤더에 배너 표시. */
  fillWithBots?: boolean;
  /** Phase 13D — 개인 턴 제한 초 (0=끔). 헤더 배지. */
  turnSeconds?: number;
  /** 현재 관전자 수 (room.spectatorIds.length). 헤더 배지. */
  spectatorCount?: number;
  /** userId→username 맵. 좌석에 #id 대신 닉네임 표시(없으면 #id 폴백). */
  usernames?: Record<number, string>;
  /** Phase 16(#3) — 매치 종료 화면에서 "메인으로" 클릭 시 호출 (방 나가기+이동). */
  onExit?: () => void;
}

/** Phase B(D-78) — 비행 카드 오버레이 상태. left/top 은 arena 기준 도착 중심,
 *  dx/dy 는 출발 좌석 중심까지의 오프셋(여기서 0 으로 transition). */
interface FlyState {
  id: number;
  cards: Card[];
  left: number;
  top: number;
  dx: number;
  dy: number;
  settled: boolean;
}

/** P2(7) — 빠른 이모지 반응 팔레트(서버 화이트리스트와 일치). */
const REACTIONS = ['👍', '😂', '😮', '😢', '🔥', '👏', '❤️', '🎉'];

const PASS_SLOT_LABEL: Record<PassSlot, string> = {
  left: t('pass.slot.left'),
  partner: t('pass.slot.partner'),
  right: t('pass.slot.right'),
};

export function GameTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  fillWithBots = false,
  turnSeconds = 0,
  spectatorCount = 0,
  usernames = {},
  onExit,
}: GameTableProps) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, sendReaction, chatPanelOpenRef } =
    useStompRoom(roomId, token);
  const [chatOpen, setChatOpen] = useState(false);
  const [reactionOpen, setReactionOpen] = useState(false);
  const unreadCount = useRoomChatStore((s) => s.unreadCount);
  const { muted, toggleMute } = useSfx();
  const cardAnimEnabled = useCardAnimStore((s) => s.enabled);
  const toggleCardAnim = useCardAnimStore((s) => s.toggle);
  const triggerEffect = useEffectStore((s) => s.trigger);
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

  useEffect(() => {
    setWishModalDismissed(false);
  }, [wishContextKey]);

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

  // 매치 종료 시 연출 1회 트리거. mySeat 으로 승/패/관전(중립) 분기 — 패배 시 트로피
  // 축하가 아니라 차분한 muted 연출(EffectsOverlay 가 tone 으로 렌더).
  useEffect(() => {
    if (!matchEnded) return;
    const won = mySeat >= 0 ? matchEnded.winningTeam === myTeam : null;
    const text =
      won === null ? `Team ${matchEnded.winningTeam} 승리` : won ? '🏆 승리!' : '아쉽게 패배';
    const tone = won === null ? 'neutral' : won ? 'win' : 'lose';
    triggerEffect('MATCH_VICTORY', text, tone);
  }, [matchEnded, myTeam, mySeat, triggerEffect]);

  const selectedCards = useMemo<Card[]>(() => {
    if (!privateHand) return [];
    return privateHand.cards.filter((c) => selectedCardKeys.has(cardKey(c)));
  }, [privateHand, selectedCardKeys]);

  // Phase 12C/#2 — 선택 카드 조합명 ("페어2" 형식, 표시용 hint. 서버가 실제 검증).
  const selectedCombo = useMemo(
    () => comboLabel(selectedCards),
    [selectedCards],
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

  // Phase B(D-78) — 카드 제출 시 제출 좌석에서 중앙 트릭으로 날아오는 FLIP 애니.
  // 토글(cardAnimEnabled) ON + reduced-motion 아님일 때만. 비행 중에는 중앙 정적
  // 카드를 visibility:hidden(레이아웃 유지)으로 가려 이중 표시를 방지.
  const arenaRef = useRef<HTMLDivElement | null>(null);
  const centerTrickRef = useRef<HTMLDivElement | null>(null);
  const prevTrickKeyRef = useRef<string | null>(null);
  const flyIdRef = useRef(0);
  const [fly, setFly] = useState<FlyState | null>(null);

  const flyCurrentTop = tableView?.currentTop ?? null;
  const flyCurrentTopSeat = tableView?.currentTopSeat ?? -1;
  const trickPlayKey = flyCurrentTop
    ? `${flyCurrentTopSeat}:${flyCurrentTop.cards.map(cardKey).join(',')}`
    : null;

  useEffect(() => {
    const prev = prevTrickKeyRef.current;
    prevTrickKeyRef.current = trickPlayKey;
    if (!trickPlayKey || trickPlayKey === prev || !flyCurrentTop) return;
    if (!cardAnimEnabled) return;
    const reduce =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce) return;

    const arena = arenaRef.current;
    const center = centerTrickRef.current;
    if (!arena || !center) return;
    const viewIdx = ((flyCurrentTopSeat - mySeat) + 4) % 4;
    const viewPos = ['s', 'w', 'n', 'e'][viewIdx];
    const seatEl = arena.querySelector(`.seat-${viewPos}`) as HTMLElement | null;
    if (!seatEl) return;

    const arenaRect = arena.getBoundingClientRect();
    const centerRect = center.getBoundingClientRect();
    const seatRect = seatEl.getBoundingClientRect();
    const centerCx = centerRect.left + centerRect.width / 2;
    const centerCy = centerRect.top + centerRect.height / 2;
    const seatCx = seatRect.left + seatRect.width / 2;
    const seatCy = seatRect.top + seatRect.height / 2;

    setFly({
      id: ++flyIdRef.current,
      cards: flyCurrentTop.cards,
      left: centerCx - arenaRect.left,
      top: centerCy - arenaRect.top,
      dx: seatCx - centerCx,
      dy: seatCy - centerCy,
      settled: false,
    });
  }, [trickPlayKey, cardAnimEnabled, flyCurrentTop, flyCurrentTopSeat, mySeat]);

  // 비행 시작(다음 프레임에 settled=true 로 transition 발동) + 종료 후 정리.
  useEffect(() => {
    if (!fly || fly.settled) return;
    const id = fly.id;
    const raf = requestAnimationFrame(() =>
      requestAnimationFrame(() =>
        setFly((f) => (f && f.id === id ? { ...f, settled: true } : f)),
      ),
    );
    const timer = window.setTimeout(
      () => setFly((f) => (f && f.id === id ? null : f)),
      420,
    );
    return () => {
      cancelAnimationFrame(raf);
      window.clearTimeout(timer);
    };
  }, [fly]);

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

  // Phase 13(#2) — 패스 3장이 모두 배정되면 별도 제출 버튼 없이 자동 제출.
  // 슬롯 재클릭으로 되돌릴 수 있는 단계가 끝난(3장 확정) 시점이라 안전.
  useEffect(() => {
    if (spectator || !isInPassing || iAmPassSubmitted) return;
    const { left, partner, right } = passCardsBySlot;
    if (left && partner && right) {
      sendAction({
        '@action': 'PASS_CARDS',
        toLeft: left,
        toPartner: partner,
        toRight: right,
      });
    }
    // sendAction 은 안정적 식별자가 아니라 의존성에서 제외 (passSelection 변화로만 트리거).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [passCardsBySlot, spectator, isInPassing, iAmPassSubmitted]);

  function handleCardClick(c: Card) {
    if (isInPassing && !iAmPassSubmitted) {
      selectPassCard(c);
    } else if (isInPlaying) {
      toggleCardSelection(c);
    }
    // Dealing 단계에서는 카드 클릭은 의미 없음 (단지 시각 정보).
  }

  if (!tableView) {
    return <p>{t('common.loading')}</p>;
  }

  const phaseLabel =
    phase === 'DEALING'
      ? `${t('game.phase.dealing')} (${dealingCardCount}${t('seat.handCardsSuffix')})`
      : phase === 'PASSING'
      ? t('game.phase.passing')
      : phase === 'PLAYING'
      ? t('game.phase.playing')
      : t('game.phase.roundEnd');

  // P3(8) — 티츄 선언 시 경기장 틴트: 그랜드=빨강 우선, 일반=파랑(라운드 동안 지속).
  const declValues = Object.values(tableView.declarations ?? {});
  const arenaTint = declValues.includes('GRAND_TICHU')
    ? 'arena-tint-grand'
    : declValues.includes('TICHU')
    ? 'arena-tint-tichu'
    : '';

  return (
    <div
      className="game-table-layout"
      style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}
    >
    <section className="game-table" style={{ flex: 1, minWidth: 0 }}>
      <EffectsOverlay />
      <ReconnectBanner connected={connected} />
      <header className="game-table-header">
        {fillWithBots && (
          <span className="solo-banner" title="이 방은 솔로 모드 — 빈 좌석은 봇이 자동 진행합니다">
            🤖 솔로 모드 (봇 {botSeats.length}명)
          </span>
        )}
        {turnSeconds > 0 && (
          <span className="turn-limit-badge" title="개인 턴 제한 — 초과 시 자동으로 다음 순서로 넘어갑니다">
            ⏱ 턴 제한 {turnSeconds}초
          </span>
        )}
        <span>
          {t('game.header.stomp')} {connected ? '●' : '○'}
        </span>
        <span>
          {t('game.header.mySeat')}: {mySeat}
        </span>
        <span>
          {t('game.header.round')} {tableView.roundNumber}
        </span>
        <span>
          {t('game.header.matchScore')} A {tableView.matchScores.A ?? 0} : {tableView.matchScores.B ?? 0} B
        </span>
        <span>{phaseLabel}</span>
        {spectatorCount > 0 && (
          <span title="관전자 수">👁 관전 {spectatorCount}</span>
        )}
        {tableView.activeWishRank !== null && (
          <span>
            {t('game.header.activeWish')}: {tableView.activeWishRank}
          </span>
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="ml-auto"
          onClick={toggleMute}
          aria-label="사운드 토글"
          title={muted ? '사운드 켜기' : '사운드 끄기'}
        >
          {muted ? '🔇' : '🔊'}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={toggleCardAnim}
          aria-label="카드 애니메이션 토글"
          title={cardAnimEnabled ? '카드 애니 끄기' : '카드 애니 켜기'}
        >
          {cardAnimEnabled ? '🎴' : '⏸'}
        </Button>
        {!spectator && (
          <div className="reaction-bar">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setReactionOpen((v) => !v)}
              aria-label="이모지 반응"
              title="이모지 반응"
            >
              😀
            </Button>
            {reactionOpen && (
              <div className="reaction-palette">
                {REACTIONS.map((e) => (
                  <button
                    type="button"
                    key={e}
                    onClick={() => {
                      sendReaction(e);
                      setReactionOpen(false);
                    }}
                  >
                    {e}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="relative"
          onClick={() => setChatOpen((v) => !v)}
          aria-label="채팅 토글"
        >
          💬 채팅
          {unreadCount > 0 && !chatOpen && (
            <span className="absolute -right-1.5 -top-1.5 min-w-[18px] rounded-full bg-destructive px-1.5 py-0.5 text-[10px] leading-none text-destructive-foreground">
              {unreadCount > 99 ? '99+' : unreadCount}
            </span>
          )}
        </Button>
      </header>

      <div className={`table-arena ${arenaTint}`} ref={arenaRef}>
        {playerIds.map((uid, seat) => {
          const ready = isInDealing && tableView.readySeats.includes(seat);
          const submitted =
            isInPassing && tableView.passingSubmittedSeats.includes(seat);
          const turnHighlight = isInPlaying && seat === tableView.currentTurnSeat;
          const disconnected = disconnectedSeats.has(seat);
          // P3(8) — 선언 좌석 불타오르는 효과(그랜드=빨강, 일반=주황).
          const decl = tableView.declarations[seat];
          const flame =
            decl && decl !== 'NONE'
              ? decl === 'GRAND_TICHU'
                ? 'flame flame-grand'
                : 'flame'
              : '';
          // Phase 8E — 본인 시점 좌석 매핑. mySeat 기준 회전 후 (S/W/N/E) 배치.
          // viewIdx 0=South(본인), 1=West(우적), 2=North(파트너), 3=East(좌적).
          const viewIdx = ((seat - mySeat) + 4) % 4;
          const viewPos = ['s', 'w', 'n', 'e'][viewIdx];
          return (
            <div
              key={uid}
              className={`seat seat-${viewPos} ${turnHighlight ? 'turn' : ''}
                         ${tableView.finishingOrder.includes(seat) ? 'finished' : ''}
                         ${ready ? 'ready' : ''}
                         ${submitted ? 'submitted' : ''}
                         ${disconnected ? 'disconnected' : ''}
                         ${flame}`}
            >
              <SeatAvatar seat={seat} userId={uid} size={34} isBot={botSeats.includes(seat)} />
              <div className="seat-id">{usernames[uid] ?? `#${uid}`}</div>
              <SeatCardStack count={tableView.handCounts[seat] ?? 0} />
              {tableView.declarations[seat] && tableView.declarations[seat] !== 'NONE' && (
                <div
                  className={`declared ${
                    tableView.declarations[seat] === 'GRAND_TICHU' ? 'grand' : ''
                  }`}
                >
                  {tableView.declarations[seat] === 'GRAND_TICHU'
                    ? '👑 그랜드 티츄!'
                    : '🔔 티츄!'}
                </div>
              )}
              {ready && <div className="status-tag">{t('seat.ready')}</div>}
              {submitted && <div className="status-tag">{t('seat.submitted')}</div>}
              {disconnected && (
                <div className="status-tag disconnected-tag">🔌 연결 끊김</div>
              )}
            </div>
          );
        })}
        {isInPlaying && (
          <div className="table-center-trick" ref={centerTrickRef}>
            {tableView.currentTop ? (
              <>
                <div className="trick-meta">
                  <span className="trick-player">
                    {usernames[playerIds[tableView.currentTopSeat]] ??
                      `#${playerIds[tableView.currentTopSeat] ?? tableView.currentTopSeat}`}
                  </span>
                  <span className="hand-type">{comboLabel(tableView.currentTop.cards)}</span>
                  {tableView.currentTop.phoenixSingle && (
                    <Badge variant="secondary" title={t('phoenix.singleTooltip')}>
                      {t('phoenix.singleBadge')}
                    </Badge>
                  )}
                </div>
                <div
                  // 새 play 마다 key 변경 → 리마운트로 등장 애니 재생. 토글 ON 일 때만.
                  // 비행 중(fly)에는 숨겨 이중 표시 방지(visibility 로 레이아웃 유지).
                  key={`${tableView.currentTopSeat}:${tableView.currentTop.cards
                    .map(cardKey)
                    .join(',')}`}
                  className={`hand-cards${cardAnimEnabled ? ' play-enter' : ''}`}
                  style={fly ? { visibility: 'hidden' } : undefined}
                >
                  {tableView.currentTop.cards.map((c) => (
                    <CardChip key={cardKey(c)} card={c} />
                  ))}
                </div>
              </>
            ) : (
              <p className="trick-empty">{t('trick.leadWaiting')}</p>
            )}
          </div>
        )}
        {fly && (
          <div
            className="trick-fly"
            aria-hidden
            style={{
              position: 'absolute',
              left: fly.left,
              top: fly.top,
              zIndex: 18,
              pointerEvents: 'none',
              transform: fly.settled
                ? 'translate(-50%, -50%) scale(1)'
                : `translate(calc(-50% + ${fly.dx}px), calc(-50% + ${fly.dy}px)) scale(0.92)`,
              opacity: fly.settled ? 1 : 0.85,
              transition: 'transform 350ms ease-out, opacity 350ms ease-out',
            }}
          >
            <div className="hand-cards">
              {fly.cards.map((c) => (
                <CardChip key={cardKey(c)} card={c} />
              ))}
            </div>
          </div>
        )}
        <div className="scoreboard" aria-label="현재 점수">
          <span className="scoreboard-round">R{tableView.roundNumber}</span>
          <span className="scoreboard-team us">
            우리 {tableView.matchScores[myTeam] ?? 0}
          </span>
          <span className="scoreboard-team them">
            상대 {tableView.matchScores[myTeam === 'A' ? 'B' : 'A'] ?? 0}
          </span>
        </div>
        {isInPlaying && turnSeconds > 0 && (
          <TurnCountdown turnSeconds={turnSeconds} />
        )}
        <ArenaChatBubbles playerIds={playerIds} mySeat={mySeat} />
        <ReactionFloats mySeat={mySeat} />
      </div>

      {!spectator && (
      <div className="my-hand">
        {privateHand ? (
          <SortableHand
            cards={handCards}
            selectedKeys={getSelectedKeys(
              selectedCardKeys,
              passSelection,
              isInPassing,
              pendingPassCardKey,
            )}
            onCardClick={handleCardClick}
            onReorder={reorderHand}
          />
        ) : (
          <p>{t('hand.loading')}</p>
        )}
      </div>
      )}

      {!spectator && (isInDealing || isInPassing || isInPlaying) && (
          <div className={`action-bar${isInPassing ? ' passing' : ''}`}>
            {isInDealing && !iAmReady && (
              <>
                {dealingCardCount === 8 && myDeclaration === 'NONE' && (
                  <Button type="button" size="sm" variant="secondary" onClick={handleDeclareGrandTichu}>
                    {t('dealing.declareGrand')}
                  </Button>
                )}
                {dealingCardCount === 14 && myDeclaration === 'NONE' && (
                  <Button type="button" size="sm" variant="secondary" onClick={handleDeclareTichu}>
                    {t('dealing.declareTichu')}
                  </Button>
                )}
                <Button type="button" size="sm" onClick={handleReady}>
                  {myDeclaration === 'NONE'
                    ? t('dealing.skip.noDeclare')
                    : t('dealing.skip.declared')}
                </Button>
              </>
            )}
            {isInDealing && iAmReady && <p className="hint">{t('dealing.waiting')}</p>}

            {isInPassing && !iAmPassSubmitted && privateHand && (
              <div className="arena-pass">
                <p className="pass-hint">
                  {pendingPassCardKey
                    ? '카드 선택됨 — 줄 사람(좌/파트너/우)을 누르세요'
                    : '먼저 손패에서 카드를 고른 뒤 줄 사람을 누르세요'}
                </p>
                <div className="pass-slots">
                  {(['left', 'partner', 'right'] as PassSlot[]).map((slot) => {
                    const c = passCardsBySlot[slot];
                    // Phase 15(#2) — 배정된 줄 사람은 선택지(버튼) 없애고 정적
                    // 칩으로 고정. 잘못 골랐으면 "초기화" 로 다시.
                    if (c) {
                      return (
                        <div key={slot} className="pass-slot filled">
                          <div className="slot-label">{PASS_SLOT_LABEL[slot]}</div>
                          <CardChip card={c} />
                          <span className="slot-done">✓</span>
                        </div>
                      );
                    }
                    return (
                      <button
                        type="button"
                        key={slot}
                        className={`pass-slot ${pendingPassCardKey ? 'droppable' : ''}`}
                        onClick={() => assignPassSlot(slot)}
                        disabled={!pendingPassCardKey}
                      >
                        <div className="slot-label">{PASS_SLOT_LABEL[slot]}</div>
                        <span className="slot-empty">{t('pass.slot.empty')}</span>
                      </button>
                    );
                  })}
                </div>
                <Button type="button" size="sm" variant="outline" onClick={clearPassSelection}>
                  {t('pass.clear')}
                </Button>
              </div>
            )}
            {isInPassing && iAmPassSubmitted && <p className="hint">{t('pass.waiting')}</p>}

            {isInPlaying && (
              <>
                {selectedCards.length > 0 && (
                  <span className="combo-hint" aria-live="polite">
                    선택: {selectedCombo}
                    {' '}({selectedCards.length}{t('seat.handCardsSuffix')})
                  </span>
                )}
                <Button
                  type="button"
                  size="sm"
                  onClick={handlePlay}
                  disabled={!myTurn || selectedCards.length === 0 || selectedCombo === '?'}
                >
                  {t('play.action.play')} ({selectedCards.length}{t('seat.handCardsSuffix')})
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  onClick={handlePass}
                  disabled={!myTurn || !tableView.currentTop}
                >
                  {t('play.action.pass')}
                </Button>
                {myDeclaration === 'NONE' &&
                  (privateHand?.cards.length ?? 0) === 14 && (
                    <Button type="button" size="sm" variant="outline" onClick={handleDeclareTichu}>
                      {t('play.action.declareTichu')}
                    </Button>
                  )}
              </>
            )}
          </div>
        )}

      {errorMessage && (
        <p className="error" onClick={() => setError(null)}>
          {errorMessage}
        </p>
      )}

      <MakeWishModal
        open={showWishModal}
        onConfirm={handleMakeWish}
        onSkip={handleSkipWish}
      />

      <GiveDragonTrickModal
        open={mustGiveDragon}
        opponentSeats={opponentSeatsOf(mySeat)}
        onConfirm={handleGiveDragon}
      />

      {matchEnded ? (
        <div className="match-ended">
          <h3>
            {mySeat >= 0
              ? matchEnded.winningTeam === myTeam
                ? '🏆 승리!'
                : '패배'
              : `Team ${matchEnded.winningTeam} 승`}
            {' — '}Team {matchEnded.winningTeam} {t('match.ended.titleSuffix')}
          </h3>
          <p>
            {t('match.ended.finalScore')} A {matchEnded.finalScores.A ?? 0} : {matchEnded.finalScores.B ?? 0} B
          </p>
          {matchEnded.mvpUserId != null && (() => {
            const mvpId = matchEnded.mvpUserId;
            const mvpSeat = playerIds.indexOf(mvpId);
            return (
              <div className="match-mvp">
                <span className="mvp-label">🏅 MVP</span>
                <SeatAvatar
                  seat={mvpSeat >= 0 ? mvpSeat : 0}
                  userId={mvpId}
                  size={44}
                  isBot={botSeats.includes(mvpSeat)}
                />
                <span className="mvp-name">
                  {usernames[mvpId] ?? `#${mvpId}`}
                  {mvpId === myUserId ? ' (나!)' : ''}
                </span>
                {matchEnded.mvpStat && (
                  <span className="mvp-stat">{matchEnded.mvpStat}</span>
                )}
              </div>
            );
          })()}
          {roundHistory.length > 0 && (
            <table className="score-history">
              <thead>
                <tr>
                  <th>R</th>
                  <th>Team A</th>
                  <th>Team B</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {roundHistory.map((r, i) => (
                  <tr key={i}>
                    <td>{i + 1}</td>
                    <td>{r.teamAScore}</td>
                    <td>{r.teamBScore}</td>
                    <td>{r.doubleVictory ? '더블 승' : ''}</td>
                  </tr>
                ))}
                <tr className="score-history-total">
                  <td>합계</td>
                  <td>{matchEnded.finalScores.A ?? 0}</td>
                  <td>{matchEnded.finalScores.B ?? 0}</td>
                  <td />
                </tr>
              </tbody>
            </table>
          )}
          <p>
            {t('match.ended.roundsPlayed')}: {matchEnded.roundsPlayed}
          </p>
          {onExit && (
            <Button type="button" className="match-exit" onClick={onExit}>
              메인으로
            </Button>
          )}
        </div>
      ) : (
        roundEnded && (
          <div className="round-ended">
            <h3>{t('round.ended.title')}</h3>
            <p>
              Team A {roundEnded.teamAScore} : {roundEnded.teamBScore} Team B
            </p>
            <p>
              {t('round.ended.firstFinisher')} {roundEnded.firstFinisherSeat}
            </p>
            <Button type="button" onClick={() => setRoundEnded(null)}>
              {t('round.ended.continue')}
            </Button>
          </div>
        )
      )}
    </section>
      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
          onClose={() => setChatOpen(false)}
        />
      )}
    </div>
  );
}

function getSelectedKeys(
  selected: Set<string>,
  passSelection: Record<PassSlot, string | null>,
  isInPassing: boolean,
  pendingPassCardKey: string | null,
): Set<string> {
  if (!isInPassing) return selected;
  const merged = new Set(selected);
  for (const v of Object.values(passSelection)) {
    if (v) merged.add(v);
  }
  if (pendingPassCardKey) merged.add(pendingPassCardKey);
  return merged;
}

/**
 * Phase 15(#6) — 경기장 내 턴 카운트다운. 서버 TURN_CHANGED 수신 시각
 * (store.turnStartedAt) + 방 turnSeconds 로 잔여 초를 클라가 로컬 계산.
 * 실제 타임아웃 강제는 서버(TurnTimeoutScheduler) 담당 — 표시는 근사.
 */
function TurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const turnStartedAt = useTichuStore((s) => s.turnStartedAt);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(id);
  }, []);
  if (turnStartedAt == null) return null;
  const elapsed = Math.floor((now - turnStartedAt) / 1000);
  const remaining = Math.max(0, turnSeconds - elapsed);
  const urgent = remaining <= 5;
  return (
    <div className={`turn-countdown ${urgent ? 'urgent' : ''}`} aria-live="off">
      ⏱ {remaining}
    </div>
  );
}
