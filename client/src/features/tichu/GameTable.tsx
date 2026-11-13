import type { Tier } from '@/api/users';
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
import { useSfx } from '@/features/sound/useSfx';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { Button } from '@/components/ui/button';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { ErrorNotice } from '@/components/notice/FloatingNotice';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useGameTableModel } from './useGameTableModel';
import { useGameActions } from './useGameActions';
import { useGameTableEffects } from './useGameTableEffects';
import { MatchEndedPanel } from './MatchEndedPanel';
import { MyHandPanel } from './MyHandPanel';
import { GameTableHeader } from './GameTableHeader';
import { TableArena } from './TableArena';
import { useVoiceChat, voiceStatusOf } from '@/features/voice/useVoiceChat';
import { RoundHistoryModal } from './RoundHistoryModal';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { TICHU_TUTORIAL } from './tutorial/tichuTutorial';
import { tichuThemeClass } from './tichuTheme';

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
  /** 내가 호스트인지. D-153 — '한 판 더'를 없애 지금은 쓰지 않는다(RoomPage 호환). */
  isHost?: boolean;
  /** Phase 16(#3) — 매치 종료 화면에서 "메인으로" 클릭 시 호출 (방 나가기+이동). */
  onExit?: () => void;
  /** D-135 — 방장 강제 종료(확인은 호출 측). RoomPage 가 방장이고 나머지가 모두 봇일 때만 넘긴다 — 없으면 버튼도 없다. */
  onAbort?: () => void;
  /** D-162 — 방장만: 끊긴 사람을 내보낸다(확인은 부르는 쪽이 — 티츄는 판이 끝난다고 알린다). */
  onKick?: (userId: number) => void;
  /** D-166 — userId → 티츄 계급(봇·게스트 없음). 좌석 이름 옆 아이콘. */
  tiers?: Record<number, Tier>;
  /** D-153 — 판이 끝나 방이 대기실로 돌아왔다(RoomPage 가 결과 화면을 유지하는 중). 매치 종료 화면의 [대기실로]가 부른다. */
  onBackToRoom?: () => void;
  /**
   * D-240 — 방이 끝났다(FINISHED 또는 대기실 복귀) — RoomPage 가 이 세션에서 본 전이 뒤 결과 화면을 유지하는 중. 손패·액션·모달·
   * [내보내기]·[게임 종료]를 감추고, 결과(MATCH_ENDED)가 없으면(강제 종료) "게임이 종료되었습니다." + [메인으로]를 띄운다.
   */
  roomFinished?: boolean;
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
  onExit,
  onAbort,
  onKick,
  tiers,
  onBackToRoom,
  roomFinished = false,
}: GameTableProps) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, sendReaction, sendVoice, subscribeVoice, chatPanelOpenRef } =
    useStompRoom(roomId, token, tichuRoomSink);
  // D-156 — 음성 대화(무료 P2P, D-155 와 같은 훅).
  const voice = useVoiceChat({ myUserId, connected, send: sendVoice, subscribe: subscribeVoice });
  const [chatOpen, setChatOpen] = useState(false);
  // D-108 — 순수 UI 상태(서버 상태 아님)라 useGameTableModel 이 아니라 여기 둔다.
  const [roundHistoryOpen, setRoundHistoryOpen] = useState(false);
  const unreadCount = useRoomChatStore((s) => s.unreadCount);
  const { muted, toggleMute, playChime } = useSfx();
  const cardAnimEnabled = useCardAnimStore((s) => s.enabled);
  const toggleCardAnim = useCardAnimStore((s) => s.toggle);
  const triggerEffect = useEffectStore((s) => s.trigger);

  // D-186 — ⋮ 메뉴의 '규칙 보기'(자동 노출 없음 — D-154).
  const rules = useTutorialGate(TICHU_TUTORIAL.seenKey, false);

  const m = useGameTableModel({ playerIds, myUserId });
  // D-240 — 끝난 방에서는 진행 중 단계로 그리지 않는다(카드·버튼·타이머·차례 알림이 끝난 방에 남지 않게).
  const live = !roomFinished;

  const { arenaRef, centerTrickRef, fly } = useGameTableEffects({
    mySeat: m.mySeat,
    myTeam: m.myTeam,
    myTurn: live && m.myTurn,
    matchEnded: m.matchEnded,
    triggerEffect,
    playChime,
    cardAnimEnabled,
    currentTop: m.tableView?.currentTop ?? null,
    currentTopSeat: m.tableView?.currentTopSeat ?? -1,
    isInPlaying: m.isInPlaying,
    spectator,
    isInPassing: m.isInPassing,
    iAmPassSubmitted: m.iAmPassSubmitted,
    passCardsBySlot: m.passCardsBySlot,
    sendAction,
  });

  const a = useGameActions({
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
    pendingWishPlay: m.pendingWishPlay,
    setPendingWishPlay: m.setPendingWishPlay,
  });

  // D-186 — 진행 중 나가기는 되돌릴 수 없는 탈주다 — 티츄는 팀전이라 곧 판이 끝나고 우리 팀이 진다(D-75).
  // 관전자·매치 종료 뒤·방 종료 뒤(D-240)는 잃을 것이 없어 묻지 않는다.
  const exit = () => {
    if (!onExit) return;
    if (
      !spectator &&
      live &&
      !m.matchEnded &&
      !window.confirm('게임 중에 나가면 탈주로 처리되어 우리 팀이 집니다. 나가시겠습니까?')
    ) {
      return;
    }
    onExit();
  };

  const tableView = m.tableView;
  if (!tableView) {
    return <p>{t('common.loading')}</p>;
  }

  return (
    <div
      className="game-table-layout"
      style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}
    >
    <section className={`game-table${tichuThemeClass}${cardAnimEnabled ? '' : ' tc-anim-off'}`} style={{ flex: 1, minWidth: 0 }} onClick={a.handleBackgroundClick}>
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
        onAbort={live ? onAbort : undefined}
        onExit={onExit ? exit : undefined}
        onRules={rules.show}
        voice={voice}
      />

      <TableArena
        tableView={tableView}
        playerIds={playerIds}
        voiceOf={(uid) => voiceStatusOf(voice, uid)}
        mySeat={m.mySeat}
        myTeam={m.myTeam}
        myTurn={live && m.myTurn}
        usernames={usernames}
        botSeats={botSeats}
        stake={stake}
        chips={m.chips}
        disconnectedSeats={m.disconnectedSeats}
        onKickSeat={onKick && live ? (seat) => onKick(playerIds[seat]) : undefined}
        tiers={tiers}
        spectator={spectator}
        turnSeconds={turnSeconds}
        isInDealing={live && m.isInDealing}
        isInPassing={live && m.isInPassing}
        isInPlaying={live && m.isInPlaying}
        arenaTint={m.arenaTint}
        cardAnimEnabled={cardAnimEnabled}
        fly={fly}
        arenaRef={arenaRef}
        centerTrickRef={centerTrickRef}
        selectedCards={m.selectedCards}
        selectedPlayable={m.selectedPlayable}
        onPass={a.handlePass}
        onPlay={a.handlePlay}
        onOpenRoundHistory={() => setRoundHistoryOpen(true)}
      />

      <TutorialDialog tutorial={TICHU_TUTORIAL} open={rules.open} onClose={rules.close} />
      <RoundHistoryModal
        open={roundHistoryOpen}
        onOpenChange={setRoundHistoryOpen}
        roundHistory={m.roundHistory}
        myTeam={m.myTeam}
        playerIds={playerIds}
        usernames={usernames}
      />

      {!spectator && live && (
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

      <ErrorNotice message={m.errorMessage} seq={m.errorSeq} sticky={m.errorSticky} onClose={() => m.setError(null)} />

      {live && m.lastReceived && m.lastReceived.length > 0 && (
        <PassReceivedModal
          received={m.lastReceived}
          playerIds={playerIds}
          usernames={usernames}
          onClose={m.clearReceived}
        />
      )}

      <MakeWishModal
        open={live && m.pendingWishPlay !== null}
        onConfirm={a.handleConfirmWishPlay}
        onSkipWish={a.handlePlayWithoutWish}
        onCancel={a.handleCancelWishPlay}
      />

      <GiveDragonTrickModal
        open={live && m.mustGiveDragon}
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
          onExit={onExit}
          onBackToRoom={onBackToRoom}
        />
      ) : roomFinished ? (
        // D-240 — 방은 끝났는데 매치 결과가 없다: 방장 강제 종료이거나 MATCH_ENDED 가 도착하기 직전의 찰나다(스컬킹·원카드와 같다).
        <section className="match-ended tichu-finished-note" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <div className="match-actions">
              <Button type="button" variant="outline" className="match-exit" onClick={onExit}>
                메인으로
              </Button>
            </div>
          )}
        </section>
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
