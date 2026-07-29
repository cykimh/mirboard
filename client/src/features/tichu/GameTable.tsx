import { useMemo, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useTichuStore, sortedHand } from '@/features/tichu/tichuStore';
import { useStompRoom } from '@/ws/useStompRoom';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { t } from '@/i18n/messages';
import { comboLabel, isSelectionPlayable } from './handType';
import { PassReceivedModal } from './PassReceivedModal';
import { MakeWishModal } from './MakeWishModal';
import { GiveDragonTrickModal, opponentSeatsOf } from './GiveDragonTrickModal';
import { EffectsOverlay } from './EffectsOverlay';
import { useEffectStore } from './effectStore';
import { useSfx } from './useSfx';
import { useCardAnimStore } from './cardAnimStore';
import { Button } from '@/components/ui/button';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useGameActions } from './useGameActions';
import { useGameTableEffects } from './useGameTableEffects';
import { MatchEndedPanel } from './MatchEndedPanel';
import { MyHandPanel } from './MyHandPanel';
import { GameTableHeader } from './GameTableHeader';
import { TableArena } from './TableArena';

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
      <GameTableHeader
        fillWithBots={fillWithBots}
        botSeats={botSeats}
        turnSeconds={turnSeconds}
        stake={stake}
        spectatorCount={spectatorCount}
        connected={connected}
        roundNumber={tableView.roundNumber}
        phaseLabel={phaseLabel}
        activeWishRank={tableView.activeWishRank}
        spectator={spectator}
        muted={muted}
        onToggleMute={toggleMute}
        cardAnimEnabled={cardAnimEnabled}
        onToggleCardAnim={toggleCardAnim}
        onSendReaction={sendReaction}
        chatOpen={chatOpen}
        onToggleChat={() => setChatOpen((v) => !v)}
        unreadCount={unreadCount}
      />

      <TableArena
        tableView={tableView}
        playerIds={playerIds}
        mySeat={mySeat}
        myTeam={myTeam}
        myTurn={myTurn}
        usernames={usernames}
        botSeats={botSeats}
        stake={stake}
        chips={chips}
        disconnectedSeats={disconnectedSeats}
        spectator={spectator}
        turnSeconds={turnSeconds}
        isInDealing={isInDealing}
        isInPassing={isInPassing}
        isInPlaying={isInPlaying}
        arenaTint={arenaTint}
        cardAnimEnabled={cardAnimEnabled}
        fly={fly}
        arenaRef={arenaRef}
        centerTrickRef={centerTrickRef}
        selectedCards={selectedCards}
        selectedPlayable={selectedPlayable}
        onPass={handlePass}
        onPlay={handlePlay}
      />

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
