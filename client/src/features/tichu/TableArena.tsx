import type { MutableRefObject } from 'react';
import { t } from '@/i18n/messages';
import type { Card, TableView } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ArenaChatBubbles } from '@/features/chat/ArenaChatBubbles';
import { comboLabel } from './handType';
import { CardChip } from './CardChip';
import { SeatAvatar } from './SeatAvatar';
import { SeatCardStack } from './SeatCardStack';
import { ReactionFloats } from './ReactionFloats';
import { TurnCountdown } from './TurnCountdown';
import type { FlyState } from './useGameTableEffects';

interface TableArenaProps {
  tableView: TableView;
  playerIds: number[];
  mySeat: number;
  myTeam: 'A' | 'B';
  myTurn: boolean;
  usernames: Record<number, string>;
  botSeats: number[];
  stake: number;
  chips: Record<number, number>;
  disconnectedSeats: Set<number>;
  spectator: boolean;
  turnSeconds: number;
  isInDealing: boolean;
  isInPassing: boolean;
  isInPlaying: boolean;
  /** P3(8) — 티츄 선언 시 경기장 틴트 클래스 ('' | arena-tint-tichu | arena-tint-grand). */
  arenaTint: string;
  cardAnimEnabled: boolean;
  /** 카드 비행 애니 상태 + DOM 앵커 (useGameTableEffects 가 소유). */
  fly: FlyState | null;
  arenaRef: MutableRefObject<HTMLDivElement | null>;
  centerTrickRef: MutableRefObject<HTMLDivElement | null>;
  selectedCards: Card[];
  selectedPlayable: boolean;
  onPass: () => void;
  onPlay: () => void;
}

/**
 * 경기장(.table-arena) — 좌석 4개(본인 시점 회전), 중앙 트릭, 비행 카드 오버레이,
 * 스코어보드, 턴 카운트다운, 채팅 말풍선/이모지, 내 좌석 양옆 패스/내기 버튼.
 *
 * D-87 에서 GameTable 에서 분리. 마크업·클래스명·조건 모두 이동 전과 동일하다.
 * arenaRef/centerTrickRef 는 비행 애니 좌표 계산의 앵커라 훅과 같은 ref 를 써야 한다.
 */
export function TableArena({
  tableView,
  playerIds,
  mySeat,
  myTeam,
  myTurn,
  usernames,
  botSeats,
  stake,
  chips,
  disconnectedSeats,
  spectator,
  turnSeconds,
  isInDealing,
  isInPassing,
  isInPlaying,
  arenaTint,
  cardAnimEnabled,
  fly,
  arenaRef,
  centerTrickRef,
  selectedCards,
  selectedPlayable,
  onPass,
  onPlay,
}: TableArenaProps) {
  return (
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
              onClick={onPass}
              disabled={!myTurn || !tableView.currentTop}
            >
              {t('play.action.pass')}
            </Button>
          </div>
          <div className="seat-action-group right">
            <Button
              type="button"
              className="seat-action-btn play"
              onClick={onPlay}
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
  );
}
