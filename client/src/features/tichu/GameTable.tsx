import { useMemo, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useTichuStore, sortedHand } from '@/features/tichu/tichuStore';
import { useStompRoom } from '@/ws/useStompRoom';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { t } from '@/i18n/messages';
import { comboLabel, isSelectionPlayable } from './handType';
import { CardChip } from './CardChip';
import { SeatAvatar } from './SeatAvatar';
import { SeatCardStack } from './SeatCardStack';
import { PassReceivedModal } from './PassReceivedModal';
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
import { TurnCountdown } from './TurnCountdown';
import { useGameActions } from './useGameActions';
import { useGameTableEffects } from './useGameTableEffects';
import { MatchEndedPanel } from './MatchEndedPanel';
import { MyHandPanel } from './MyHandPanel';

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
  /** D-81 — 판돈(가상 칩, 0=내기 없음). 헤더 배지 + 매치 종료 칩 증감 표시. */
  stake?: number;
  /** 현재 관전자 수 (room.spectatorIds.length). 헤더 배지. */
  spectatorCount?: number;
  /** userId→username 맵. 좌석에 #id 대신 닉네임 표시(없으면 #id 폴백). */
  usernames?: Record<number, string>;
  /** D-82 — 내가 호스트인지 (매치 종료 화면 '한 판 더' 버튼 노출용). */
  isHost?: boolean;
  /** Phase 16(#3) — 매치 종료 화면에서 "메인으로" 클릭 시 호출 (방 나가기+이동). */
  onExit?: () => void;
}

/** P2(7) — 빠른 이모지 반응 팔레트(서버 화이트리스트와 일치). */
const REACTIONS = ['👍', '😂', '😮', '😢', '🔥', '👏', '❤️', '🎉'];

export function GameTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  fillWithBots = false,
  turnSeconds = 0,
  stake = 0,
  spectatorCount = 0,
  usernames = {},
  isHost = false,
  onExit,
}: GameTableProps) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, sendReaction, chatPanelOpenRef } =
    useStompRoom(roomId, token);
  const [chatOpen, setChatOpen] = useState(false);
  const [reactionOpen, setReactionOpen] = useState(false);
  const unreadCount = useRoomChatStore((s) => s.unreadCount);
  const { muted, toggleMute, playChime } = useSfx();
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

  const { arenaRef, centerTrickRef, fly } = useGameTableEffects({
    mySeat,
    myTeam,
    myTurn,
    wishContextKey,
    setWishModalDismissed,
    matchEnded,
    triggerEffect,
    playChime,
    cardAnimEnabled,
    currentTop: tableView?.currentTop ?? null,
    currentTopSeat: tableView?.currentTopSeat ?? -1,
    spectator,
    isInPassing,
    iAmPassSubmitted,
    passCardsBySlot,
    sendAction,
  });

  const {
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
  } = useGameActions({
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
  });

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
    <section className="game-table" style={{ flex: 1, minWidth: 0 }} onClick={handleBackgroundClick}>
      <EffectsOverlay />
      <ReconnectBanner connected={connected} />
      <header className="game-table-header">
        <div className="header-status">
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
          {stake > 0 && (
            <span className="turn-limit-badge" title="내기 방 — 판돈(가상 칩). 승팀이 가져갑니다">
              💰 판돈 {stake}칩
            </span>
          )}
          <span
            className="conn-indicator"
            title={connected ? '서버 연결됨' : '서버 연결 끊김'}
            aria-label={connected ? '서버 연결됨' : '서버 연결 끊김'}
          >
            <span className={`conn-dot ${connected ? 'on' : 'off'}`} aria-hidden="true" />
          </span>
          <span>
            {t('game.header.round')} {tableView.roundNumber}
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
        </div>
        <div className="header-controls">
          <Button
            type="button"
            variant="outline"
            size="sm"
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
        </div>
      </header>

      <div className={`table-arena ${arenaTint}`} ref={arenaRef}>
        {playerIds.map((uid, seat) => {
          const ready = isInDealing && tableView.readySeats.includes(seat);
          const submitted =
            isInPassing && tableView.passingSubmittedSeats.includes(seat);
          const turnHighlight = isInPlaying && seat === tableView.currentTurnSeat;
          const disconnected = disconnectedSeats.has(seat);
          // 티츄 선언 시 좌석 사각형이 아니라 아바타 원이 깜빡이고 종(🔔) 배지가
          // 흔들린다(#3,#4). 'grand'=적색, 'tichu'=금색.
          const decl = tableView.declarations[seat];
          const declared: 'tichu' | 'grand' | null =
            decl && decl !== 'NONE' ? (decl === 'GRAND_TICHU' ? 'grand' : 'tichu') : null;
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
                         ${disconnected ? 'disconnected' : ''}`}
            >
              <SeatAvatar
                seat={seat}
                userId={uid}
                size={34}
                isBot={botSeats.includes(seat)}
                declared={declared}
              />
              {/* #2 내(남) 계정은 표시 안 함. #3 상대/파트너는 배지에 계정명 표시(팀색 유지:
                  나·파트너=우리/초록, 좌·우=상대/빨강). 긴 이름은 말줄임 + title 로 풀네임. */}
              <div
                className={`seat-team ${viewPos === 'w' || viewPos === 'e' ? 'them' : 'us'}`}
                title={viewPos === 's' ? undefined : usernames[uid] ?? `#${uid}`}
              >
                {viewPos === 's' ? '나' : usernames[uid] ?? `#${uid}`}
              </div>
              {stake > 0 && (
                <div className="seat-chips" title="테이블 칩">
                  💰 {(chips[uid] ?? 0).toLocaleString()}
                </div>
              )}
              {/* 내 좌석(남)은 실제 손패가 아래에 보이므로 좌석 카드 스택을 렌더하지 않음(요청). */}
              {viewPos !== 's' && (
                <SeatCardStack
                  count={tableView.handCounts[seat] ?? 0}
                  viewPos={viewPos as 's' | 'w' | 'n' | 'e'}
                />
              )}
              {tableView.declarations[seat] && tableView.declarations[seat] !== 'NONE' && (
                <div
                  className={`declared ${
                    tableView.declarations[seat] === 'GRAND_TICHU' ? 'grand' : ''
                  }`}
                >
                  {/* 종(🔔)은 아바타 배지로 보여주므로(#4) 텍스트엔 아이콘 중복 제거. */}
                  {tableView.declarations[seat] === 'GRAND_TICHU'
                    ? '그랜드 티츄!'
                    : '티츄!'}
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
        {/* 패스/취소(좌) · 내기(우)를 내 좌석 양옆에(요청: 내기↔패스 좌우 스왑). 왼쪽
            그룹은 우측앵커라 패스를 안쪽(중앙 쪽)·취소를 바깥(왼쪽)에 둔다. 내기는
            "진짜로 낼 수 있을 때만" 활성(selectedPlayable). 두 그룹을 좌석 바깥으로
            앵커해 취소가 늘어도 좌석/버튼을 침범하지 않는다. */}
        {!spectator && isInPlaying && (
          <div className="arena-seat-actions" aria-label="내 차례 액션">
            <div className="seat-action-group left">
              <Button
                type="button"
                className="seat-action-btn pass"
                variant="secondary"
                onClick={handlePass}
                disabled={!myTurn || !tableView.currentTop}
              >
                {t('play.action.pass')}
              </Button>
            </div>
            <div className="seat-action-group right">
              <Button
                type="button"
                className="seat-action-btn play"
                onClick={handlePlay}
                disabled={!myTurn || !selectedPlayable}
              >
                {t('play.action.play')}
                {selectedCards.length > 0
                  ? ` (${selectedCards.length}${t('seat.handCardsSuffix')})`
                  : ''}
              </Button>
            </div>
          </div>
        )}
      </div>

      {!spectator && (
        <MyHandPanel
          privateHand={privateHand}
          handCards={handCards}
          selectedCardKeys={selectedCardKeys}
          passSelection={passSelection}
          pendingPassCardKey={pendingPassCardKey}
          passCardsBySlot={passCardsBySlot}
          selectedCards={selectedCards}
          selectedCombo={selectedCombo}
          selectedPlayable={selectedPlayable}
          isInDealing={isInDealing}
          isInPassing={isInPassing}
          isInPlaying={isInPlaying}
          iAmReady={iAmReady}
          iAmPassSubmitted={iAmPassSubmitted}
          dealingCardCount={dealingCardCount}
          myDeclaration={myDeclaration}
          onCardClick={handleCardClick}
          onReorder={reorderHand}
          onAssignPassSlot={assignPassSlot}
          onClearPassSelection={clearPassSelection}
          onDeclareTichu={handleDeclareTichu}
          onDeclareGrandTichu={handleDeclareGrandTichu}
          onReady={handleReady}
        />
      )}

      {errorMessage && (
        <p className="error" onClick={() => setError(null)}>
          {errorMessage}
        </p>
      )}

      {lastReceived && lastReceived.length > 0 && (
        <PassReceivedModal
          received={lastReceived}
          playerIds={playerIds}
          usernames={usernames}
          onClose={clearReceived}
        />
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
        <MatchEndedPanel
          matchEnded={matchEnded}
          roundHistory={roundHistory}
          playerIds={playerIds}
          myUserId={myUserId}
          mySeat={mySeat}
          myTeam={myTeam}
          usernames={usernames}
          botSeats={botSeats}
          stake={stake}
          chips={chips}
          chipDeltas={chipDeltas}
          spectator={spectator}
          isHost={isHost}
          onRematch={handleRematch}
          onExit={onExit}
        />
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
