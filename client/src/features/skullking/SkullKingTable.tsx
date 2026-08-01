import { useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { skullkingRoomSink } from './skullkingRoomSink';
import { bidsRevealed, useSkullKingStore } from './skullkingStore';
import { leadSuitOf, seatAccent, seatMinWidth, viewOrder } from './seatLayout';
import { SkullSeatCard } from './SkullSeatCard';
import { SkullTrickRow } from './SkullTrickRow';
import { SkullCardChip } from './SkullCardChip';
import { SkullHandPanel } from './SkullHandPanel';
import { BidPanel } from './BidPanel';
import { SkullMatchEndPanel, SkullRoundEndPanel } from './SkullScorePanels';

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
}

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
}: Props) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef } = useStompRoom(
    roomId,
    token,
    skullkingRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const chatUnread = useRoomChatStore((s) => s.unreadCount);

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
            onClick={() => setChatOpen((v) => !v)}
          >
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          {onExit && (
            <button type="button" className="sk-badge" onClick={onExit}>
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

      {s.phase === 'ROUND_END' && !s.matchEnded && (
        <SkullRoundEndPanel
          roundNumber={s.roundNumber}
          scores={s.roundScores}
          cumulativeScores={s.cumulativeScores}
          nameOf={nameOf}
        />
      )}

      {s.matchEnded && (
        <SkullMatchEndPanel
          match={s.matchEnded}
          mySeat={mySeat}
          nameOf={nameOf}
          onExit={onExit}
        />
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
            {myTurn && <span className="sk-badge">내 차례</span>}
          </div>

          {s.phase === 'BIDDING' && (
            <BidPanel
              handSize={s.handSize}
              myBid={s.myBid}
              pendingCount={pendingBids}
              onPlaceBid={placeBid}
            />
          )}

          {s.phase === 'PLAYING' && (
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
