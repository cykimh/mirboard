import { useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { skullkingRoomSink } from './skullkingRoomSink';
import { bidsRevealed, lastRoundResult, useSkullKingStore } from './skullkingStore';
import { leadSuitOf, seatAccent, seatMinWidth, viewOrder } from './seatLayout';
import { SkullSeatCard } from './SkullSeatCard';
import { SkullTrickRow } from './SkullTrickRow';
import { SkullCardChip } from './SkullCardChip';
import { SkullHandPanel } from './SkullHandPanel';
import { BidPanel } from './BidPanel';
import { SkullMatchEndPanel, SkullRoundResultPanel } from './SkullScorePanels';
import { SkullRoundHistoryModal } from './SkullRoundHistoryModal';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { SKULL_KING_TUTORIAL } from './tutorial/skullkingTutorial';

interface Props {
  roomId: string;
  playerIds: number[];
  myUserId: number;
  spectator?: boolean;
  botSeats?: number[];
  usernames?: Record<number, string>;
  turnSeconds?: number;
  spectatorCount?: number;
  onExit?: () => void;
  /**
   * D-120 — 방이 이미 FINISHED 인데 이 세션이 IN_GAME→FINISHED 전이를 봐서 게임판을
   * 유지하는 중. 입력을 숨기고, 나가기에 탈주 확인을 묻지 않는다.
   */
  roomFinished?: boolean;
}

const signed = (n: number) => (n > 0 ? `+${n}` : `${n}`);

/**
 * 스컬킹 게임판 조립 루트 (D-103, S6). 티츄 `GameTable` 과 같은 구조로 자기 소켓을 소유하고
 * 자기 sink 를 주입한다 — 그래서 두 게임의 코드 경로가 겹치지 않는다.
 *
 * <p>기하는 **Row-Flow**다: 상대 좌석은 `auto-fit` 그리드에 정상 흐름으로 놓여 2~8인이
 * 폭 미디어 없이 줄바꿈되고, 트릭은 재생순 레일로 늘어놓는다. 좌석 수의 권위값은
 * `tableView.seats.length`(스토어의 `seats`)이며 `playerIds` 는 이름·아바타 조회용이다.
 */
export function SkullKingTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  usernames = {},
  turnSeconds = 0,
  spectatorCount = 0,
  onExit,
  roomFinished = false,
}: Props) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef } = useStompRoom(
    roomId,
    token,
    skullkingRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const chatUnread = useRoomChatStore((s) => s.unreadCount);
  // D-120 — 점수표 모달·결과 패널 닫기는 서버 상태가 아니라 이 화면의 로컬 상태다.
  const [historyOpen, setHistoryOpen] = useState(false);
  const [dismissedRound, setDismissedRound] = useState<number | null>(null);
  // D-121 — 게임판에서는 수동으로만 연다(자동 노출 없음). 입찰·턴 타이머가 계속 흐른다.
  const rules = useTutorialGate(SKULL_KING_TUTORIAL.seenKey, false);

  const s = useSkullKingStore();
  const revealed = bidsRevealed(s);
  const seatCount = s.seats.length || playerIds.length;
  const mySeat = spectator ? -1 : s.mySeat;
  const opponents = viewOrder(seatCount, mySeat);
  const leadSuit = leadSuitOf(s.trick);
  const myTurn = !spectator && mySeat >= 0 && s.currentTurnSeat === mySeat;

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };

  const mySeatView = s.seats.find((x) => x.seat === mySeat) ?? null;
  const completedTricks = s.seats.reduce((n, x) => n + x.tricksWon, 0);
  const pendingBids = s.seats.filter((x) => !x.hasBid).length;
  const allSeats = Array.from({ length: seatCount }, (_, i) => i);

  // D-120 — 직전 라운드 결과. 서버는 라운드 사이에 멈추지 않으므로 다음 라운드 예측 중에
  // 보여 준다. 닫은 라운드는 다시 띄우지 않는다(다음 라운드 결과는 새로 뜬다).
  const lastResult = lastRoundResult(s);
  const showResult = lastResult !== null && lastResult.roundNumber !== dismissedRound;
  const myLast = lastResult && mySeat >= 0 ? lastResult.scores[mySeat] : undefined;

  const placeBid = (bid: number) => sendAction({ '@action': 'PLACE_BID', bid });

  const playSelected = () => {
    if (s.selectedIndex === null) return;
    const card = s.hand[s.selectedIndex];
    if (!card) return;
    sendAction({
      '@action': 'PLAY_CARD',
      card,
      ...(card.special === 'TIGRESS' && s.tigressDeclaration
        ? { declaredAs: s.tigressDeclaration }
        : {}),
    });
  };

  // D-110 — 진행 중 나가기는 되돌릴 수 없는 탈주다(유령 좌석 자동조종, D-104).
  // 관전자·매치 종료 후·방 종료 후(D-120)는 잃을 것이 없어 묻지 않는다.
  const exit = () => {
    if (!onExit) return;
    if (
      !spectator &&
      !s.matchEnded &&
      !roomFinished &&
      !window.confirm(
        '게임 중에 나가면 탈주로 처리되고, 내 자리는 남은 라운드 동안 자동으로 플레이됩니다. 나가시겠습니까?',
      )
    ) {
      return;
    }
    onExit();
  };

  return (
    <div
      className="sk-table"
      style={{ ['--sk-seat-min' as string]: seatMinWidth(seatCount) }}
    >
      <header className="sk-header">
        <div className="sk-header-badges">
          <span className="sk-badge sk-badge-round">
            라운드 {s.roundNumber} / 10
          </span>
          <span className="sk-badge">{seatCount}인</span>
          <span className="sk-badge">손패 {s.handSize}장</span>
          {turnSeconds > 0 && <span className="sk-badge">턴 {turnSeconds}초</span>}
          {spectatorCount > 0 && (
            <span className="sk-badge">관전 {spectatorCount}</span>
          )}
          {spectator && <span className="sk-badge">관전 모드</span>}
          <span className="sk-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="sk-header-badges">
          <button
            type="button"
            className="sk-badge"
            aria-haspopup="dialog"
            onClick={() => setHistoryOpen(true)}
          >
            점수표
          </button>
          <button
            type="button"
            className="sk-badge"
            onClick={() => setChatOpen((v) => !v)}
          >
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button
            type="button"
            className="sk-badge"
            onClick={rules.show}
            title="게임 방법 다시 보기 — 턴 타이머는 계속 흐릅니다"
          >
            규칙
          </button>
          {onExit && (
            <button type="button" className="sk-badge" onClick={exit}>
              나가기
            </button>
          )}
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && <p className="sk-error">{s.errorMessage}</p>}

      <div className="sk-seats">
        {opponents.map((seat) => {
          const view = s.seats.find((x) => x.seat === seat);
          if (!view) return null;
          return (
            <SkullSeatCard
              key={seat}
              seat={view}
              userId={playerIds[seat]}
              username={usernames[playerIds[seat]]}
              isBot={botSeats.includes(seat)}
              isTurn={s.currentTurnSeat === seat}
              isDeserted={s.desertedSeats.includes(seat)}
              isDisconnected={s.disconnectedSeats.has(seat)}
              bidsRevealed={revealed}
            />
          );
        })}
      </div>

      {s.phase === 'PLAYING' && (
        <SkullTrickRow
          trick={s.trick}
          settled={s.settledTrick}
          currentTurnSeat={s.currentTurnSeat}
          handSize={s.handSize}
          completedTricks={completedTricks}
          nameOf={nameOf}
        />
      )}

      {s.matchEnded && (
        <SkullMatchEndPanel
          match={s.matchEnded}
          mySeat={mySeat}
          nameOf={nameOf}
          onExit={onExit}
          completedRounds={s.completedRounds}
          seats={allSeats}
          desertedSeats={s.desertedSeats}
        />
      )}

      {/* D-120 — 방은 끝났는데 매치 결과가 없다: 호스트 강제 종료이거나, MATCH_ENDED 가
          도착하기 직전의 찰나다. */}
      {roomFinished && !s.matchEnded && (
        <section className="sk-finished-note" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="sk-play" onClick={exit}>
              메인으로
            </button>
          )}
        </section>
      )}

      {!spectator && mySeat >= 0 && (
        <>
          <div
            className={`sk-me${myTurn ? ' sk-me-turn' : ''}`}
            style={{ ['--sk-accent' as string]: seatAccent(mySeat) }}
          >
            <strong>{nameOf(mySeat)} (나)</strong>
            <span className="sk-stat">
              <span className="sk-stat-key">예측</span>
              <span className="sk-stat-val">
                {revealed
                  ? (mySeatView?.bid ?? '—')
                  : (s.myBid ?? '미제출')}
              </span>
            </span>
            <span className="sk-stat">
              <span className="sk-stat-key">획득</span>
              <span className="sk-stat-val">{mySeatView?.tricksWon ?? 0}</span>
            </span>
            <span className="sk-stat">
              <span className="sk-stat-key">누적</span>
              <span className="sk-stat-val">{s.cumulativeScores[mySeat] ?? 0}</span>
            </span>
            {/* D-120 — 결과 패널이 화면 아래로 밀려도 내 직전 결과는 여기서 바로 보인다. */}
            {s.phase === 'BIDDING' && lastResult && myLast && (
              <span className="sk-stat" title={`라운드 ${lastResult.roundNumber} 결과`}>
                <span className="sk-stat-key">직전</span>
                <span
                  className={`sk-stat-val ${
                    myLast.bid === myLast.won ? 'sk-history-hit' : 'sk-history-miss'
                  }`}
                >
                  R{lastResult.roundNumber} {signed(myLast.total)}{' '}
                  {myLast.bid === myLast.won ? '✓' : '✗'}
                </span>
              </span>
            )}
            {myTurn && <span className="sk-badge">내 차례</span>}
          </div>

          {s.phase === 'BIDDING' && !roomFinished && (
            <BidPanel
              handSize={s.handSize}
              myBid={s.myBid}
              pendingCount={pendingBids}
              onPlaceBid={placeBid}
            />
          )}

          {s.phase === 'PLAYING' && !roomFinished && (
            <SkullHandPanel
              hand={s.hand}
              selectedIndex={s.selectedIndex}
              tigressDeclaration={s.tigressDeclaration}
              leadSuit={leadSuit}
              myTurn={myTurn}
              onSelect={s.selectCard}
              onDeclare={s.setTigressDeclaration}
              onPlay={playSelected}
            />
          )}

          {s.phase === 'BIDDING' && s.hand.length > 0 && (
            <section className="my-hand sk-hand" aria-label="내 손패 (예측 중)">
              <div className="hand-cards overlap sk-hand-cards">
                {s.hand.map((card, i) => (
                  <SkullCardChip key={i} card={card} />
                ))}
              </div>
            </section>
          )}
        </>
      )}

      {/* D-120 — 입력·손패 **아래**. 입력 동선을 밀어내지 않고, 관전자에게는 좌석 바로 아래다. */}
      {showResult && lastResult && (
        <SkullRoundResultPanel
          round={lastResult}
          cumulativeScores={s.cumulativeScores}
          mySeat={mySeat}
          nameOf={nameOf}
          onOpenHistory={() => setHistoryOpen(true)}
          onDismiss={() => setDismissedRound(lastResult.roundNumber)}
        />
      )}

      <SkullRoundHistoryModal
        open={historyOpen}
        onOpenChange={setHistoryOpen}
        rounds={s.completedRounds}
        seats={allSeats}
        totals={s.cumulativeScores}
        mySeat={mySeat}
        nameOf={nameOf}
        desertedSeats={s.desertedSeats}
      />

      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
          onClose={() => setChatOpen(false)}
          roomId={roomId}
        />
      )}

      <TutorialDialog tutorial={SKULL_KING_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
