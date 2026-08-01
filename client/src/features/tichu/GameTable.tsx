import { useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { tichuRoomSink } from './tichuRoomSink';
import { t } from '@/i18n/messages';
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
import { useGameTableModel } from './useGameTableModel';
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

/**
 * 게임판 조립 루트. 직접 하는 일은 세 가지뿐이다 — 화면 전역 훅(소켓·사운드·
 * 카드애니·이펙트)을 켜고, 뷰 모델(`m`)과 액션(`a`)을 만들고, 그 둘을 자식에 꽂는다.
 *
 * 상태·파생은 {@link useGameTableModel}, 부수효과는 {@link useGameTableEffects},
 * 사용자 액션은 {@link useGameActions}, 마크업은 4개 프레젠테이션 컴포넌트가
 * 나눠 갖는다 (D-87).
 */
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
    useStompRoom(roomId, token, tichuRoomSink);
  const [chatOpen, setChatOpen] = useState(false);
  const unreadCount = useRoomChatStore((s) => s.unreadCount);
  const { muted, toggleMute, playChime } = useSfx();
  const cardAnimEnabled = useCardAnimStore((s) => s.enabled);
  const toggleCardAnim = useCardAnimStore((s) => s.toggle);
  const triggerEffect = useEffectStore((s) => s.trigger);

  const m = useGameTableModel({ playerIds, myUserId });

  const { arenaRef, centerTrickRef, fly } = useGameTableEffects({
    mySeat: m.mySeat,
    myTeam: m.myTeam,
    myTurn: m.myTurn,
    wishContextKey: m.wishContextKey,
    setWishModalDismissed: m.setWishModalDismissed,
    matchEnded: m.matchEnded,
    triggerEffect,
    playChime,
    cardAnimEnabled,
    currentTop: m.tableView?.currentTop ?? null,
    currentTopSeat: m.tableView?.currentTopSeat ?? -1,
    spectator,
    isInPassing: m.isInPassing,
    iAmPassSubmitted: m.iAmPassSubmitted,
    passCardsBySlot: m.passCardsBySlot,
    sendAction,
  });

  const a = useGameActions({
    roomId,
    token,
    sendAction,
    selectedCards: m.selectedCards,
    selectedCardKeys: m.selectedCardKeys,
    isInPlaying: m.isInPlaying,
    isInPassing: m.isInPassing,
    iAmPassSubmitted: m.iAmPassSubmitted,
    clearSelection: m.clearSelection,
    toggleCardSelection: m.toggleCardSelection,
    selectPassCard: m.selectPassCard,
    setError: m.setError,
    setWishModalDismissed: m.setWishModalDismissed,
  });

  const tableView = m.tableView;
  if (!tableView) {
    return <p>{t('common.loading')}</p>;
  }

  return (
    <div
      className="game-table-layout"
      style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}
    >
    <section className="game-table" style={{ flex: 1, minWidth: 0 }} onClick={a.handleBackgroundClick}>
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
        phaseLabel={m.phaseLabel}
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
        mySeat={m.mySeat}
        myTeam={m.myTeam}
        myTurn={m.myTurn}
        usernames={usernames}
        botSeats={botSeats}
        stake={stake}
        chips={m.chips}
        disconnectedSeats={m.disconnectedSeats}
        spectator={spectator}
        turnSeconds={turnSeconds}
        isInDealing={m.isInDealing}
        isInPassing={m.isInPassing}
        isInPlaying={m.isInPlaying}
        arenaTint={m.arenaTint}
        cardAnimEnabled={cardAnimEnabled}
        fly={fly}
        arenaRef={arenaRef}
        centerTrickRef={centerTrickRef}
        selectedCards={m.selectedCards}
        selectedPlayable={m.selectedPlayable}
        onPass={a.handlePass}
        onPlay={a.handlePlay}
      />

      {!spectator && (
        <MyHandPanel
          privateHand={m.privateHand}
          handCards={m.handCards}
          selectedCardKeys={m.selectedCardKeys}
          passSelection={m.passSelection}
          pendingPassCardKey={m.pendingPassCardKey}
          passCardsBySlot={m.passCardsBySlot}
          selectedCards={m.selectedCards}
          selectedCombo={m.selectedCombo}
          selectedPlayable={m.selectedPlayable}
          isInDealing={m.isInDealing}
          isInPassing={m.isInPassing}
          isInPlaying={m.isInPlaying}
          iAmReady={m.iAmReady}
          iAmPassSubmitted={m.iAmPassSubmitted}
          dealingCardCount={m.dealingCardCount}
          myDeclaration={m.myDeclaration}
          onCardClick={a.handleCardClick}
          onReorder={m.reorderHand}
          onAssignPassSlot={m.assignPassSlot}
          onClearPassSelection={m.clearPassSelection}
          onDeclareTichu={a.handleDeclareTichu}
          onDeclareGrandTichu={a.handleDeclareGrandTichu}
          onReady={a.handleReady}
        />
      )}

      {m.errorMessage && (
        <p className="error" onClick={() => m.setError(null)}>
          {m.errorMessage}
        </p>
      )}

      {m.lastReceived && m.lastReceived.length > 0 && (
        <PassReceivedModal
          received={m.lastReceived}
          playerIds={playerIds}
          usernames={usernames}
          onClose={m.clearReceived}
        />
      )}

      <MakeWishModal
        open={m.showWishModal}
        onConfirm={a.handleMakeWish}
        onSkip={a.handleSkipWish}
      />

      <GiveDragonTrickModal
        open={m.mustGiveDragon}
        opponentSeats={opponentSeatsOf(m.mySeat)}
        onConfirm={a.handleGiveDragon}
      />

      {m.matchEnded ? (
        <MatchEndedPanel
          matchEnded={m.matchEnded}
          roundHistory={m.roundHistory}
          playerIds={playerIds}
          myUserId={myUserId}
          mySeat={m.mySeat}
          myTeam={m.myTeam}
          usernames={usernames}
          botSeats={botSeats}
          stake={stake}
          chips={m.chips}
          chipDeltas={m.chipDeltas}
          spectator={spectator}
          isHost={isHost}
          onRematch={a.handleRematch}
          onExit={onExit}
        />
      ) : (
        m.roundEnded && (
          <div className="round-ended">
            <h3>{t('round.ended.title')}</h3>
            <p>
              Team A {m.roundEnded.teamAScore} : {m.roundEnded.teamBScore} Team B
            </p>
            <p>
              {t('round.ended.firstFinisher')} {m.roundEnded.firstFinisherSeat}
            </p>
            <Button type="button" onClick={() => m.setRoundEnded(null)}>
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
          roomId={roomId}
        />
      )}
    </div>
  );
}
