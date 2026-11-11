import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import type { MutableRefObject } from 'react';
import { t } from '@/i18n/messages';
import type { Card, TableView } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ArenaChatBubbles } from './ArenaChatBubbles';
import { comboLabel } from './handType';
import { CardChip } from './CardChip';
import { SeatAvatar } from './SeatAvatar';
import { SeatCardStack } from './SeatCardStack';
import { viewPosOf } from './seatView';
import { ReactionFloats } from './ReactionFloats';
import { TurnCountdown } from './TurnCountdown';
import type { FlyState } from './useGameTableEffects';
import { ArenaFxLayer, wishLabel } from './ArenaFxLayer';
import { useEffectStore } from './effectStore';
import { lacquerTheme } from './tichuTheme';

interface TableArenaProps {
  tableView: TableView;
  playerIds: number[];
  mySeat: number;
  myTeam: 'A' | 'B';
  myTurn: boolean;
  usernames: Record<number, string>;
  /** D-156 — 좌석 사람의 음성 대화 상태(참여 중 on / 나와 연결 실패 failed). */
  voiceOf?: (userId: number) => 'on' | 'failed' | undefined;
  botSeats: number[];
  stake: number;
  chips: Record<number, number>;
  disconnectedSeats: Set<number>;
  /** D-162 — 방장만: 끊긴 좌석의 사람을 내보낸다(확인은 부르는 쪽이). */
  onKickSeat?: (seat: number) => void;
  /** D-166 — userId → 계급(봇·게스트 없음). 좌석 이름 배지 앞 아이콘. */
  tiers?: Record<number, Tier>;
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
  /** D-108 — 점수 칩 클릭 → 라운드별 점수 내역. */
  onOpenRoundHistory: () => void;
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
  voiceOf,
  botSeats,
  stake,
  chips,
  disconnectedSeats,
  onKickSeat,
  tiers = {},
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
  onOpenRoundHistory,
}: TableArenaProps) {
  // D-241 — 폭탄이 터지면 경기장이 잠깐 흔들린다(옻칠 테마). 연달아 터져도 다시 흔들리게 연출 id 로 키프레임 이름을 번갈아 쓴다.
  const bombId = useEffectStore((s) =>
    s.active && (s.active.kind === 'BOMB' || s.active.kind === 'STRAIGHT_FLUSH_BOMB') ? s.active.id : null,
  );
  const shake = lacquerTheme && bombId !== null ? ` tc-shake-${bombId % 2 === 0 ? 'a' : 'b'}` : '';
  const myScore = tableView.matchScores[myTeam] ?? 0;
  const theirScore = tableView.matchScores[myTeam === 'A' ? 'B' : 'A'] ?? 0;
  return (
    <div className={`table-arena ${arenaTint}${shake}`} ref={arenaRef}>
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
        // 본인 시점 좌석 매핑(D-132 시계 방향): 나=South(아래), +1=East(왼쪽·다음 차례 상대),
        // +2=North(파트너), +3=West(오른쪽·직전 차례 상대).
        const viewPos = viewPosOf(seat, mySeat);
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
              {tiers[uid] && <TierBadge tier={tiers[uid]} size={14} />}
              {viewPos === 's' ? '나' : usernames[uid] ?? `#${uid}`}
              {voiceOf?.(uid) && (
                <span
                  className={`seat-voice${voiceOf(uid) === 'failed' ? ' seat-voice-failed' : ''}`}
                  aria-label={voiceOf(uid) === 'failed' ? '음성 연결 안 됨' : '음성 대화 참여 중'}
                  title={voiceOf(uid) === 'failed' ? '음성 연결 안 됨' : '음성 대화 참여 중'}
                >
                  {voiceOf(uid) === 'failed' ? ' 🎧✕' : ' 🎧'}
                </span>
              )}
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
                viewPos={viewPos}
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
            {disconnected && onKickSeat && seat !== mySeat && (
              <button type="button" className="status-tag kick-btn" onClick={() => onKickSeat(seat)}>
                내보내기
              </button>
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
              {/* D-241 — 카드가 내려앉는 자리에 금빛 고리 한 번(낼 때마다 key 로 다시 돈다). */}
              {lacquerTheme && cardAnimEnabled && (
                <span
                  key={`ring:${tableView.currentTopSeat}:${tableView.currentTop.cards.map(cardKey).join(',')}`}
                  className="tc-land-ring"
                  aria-hidden="true"
                />
              )}
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
            // D-241 — 옻칠 테마는 살짝 기울어 날아와 조금 지나쳤다 내려앉는다(같은 길이 — 정리 타이머 420ms 안).
            transform: fly.settled
              ? 'translate(-50%, -50%) scale(1)'
              : lacquerTheme
                ? `translate(calc(-50% + ${fly.dx}px), calc(-50% + ${fly.dy}px)) scale(0.78) rotate(-9deg)`
                : `translate(calc(-50% + ${fly.dx}px), calc(-50% + ${fly.dy}px)) scale(0.92)`,
            opacity: fly.settled ? 1 : 0.85,
            transition: lacquerTheme
              ? 'transform 360ms cubic-bezier(0.2, 0.85, 0.3, 1.22), opacity 240ms ease-out'
              : 'transform 350ms ease-out, opacity 350ms ease-out',
          }}
        >
          <div className="hand-cards">
            {fly.cards.map((c) => (
              <CardChip key={cardKey(c)} card={c} />
            ))}
          </div>
        </div>
      )}
      {/* D-108 — 누르면 라운드별 점수. 완료 라운드가 0개여도 비활성화하지 않는다:
          안 눌리는 버튼은 기능이 없는 것처럼 읽힌다(모달이 빈 상태를 안내). */}
      <button
        type="button"
        className="scoreboard"
        aria-label="현재 점수 — 누르면 라운드별 점수"
        aria-haspopup="dialog"
        onClick={onOpenRoundHistory}
      >
        <span className="scoreboard-round">R{tableView.roundNumber}</span>
        {/* D-241 — 점수가 바뀌면 key 가 바뀌어 다시 마운트되고, 옻칠 테마 CSS 가 한 번 튀어 오르게 한다. */}
        <span key={`us:${myScore}`} className="scoreboard-team us">
          우리 {myScore}
        </span>
        <span key={`them:${theirScore}`} className="scoreboard-team them">
          상대 {theirScore}
        </span>
      </button>
      {lacquerTheme && isInPlaying && tableView.activeWishRank !== null && (
        // D-241 — 걸려 있는 소원 숫자를 경기장 안 작은 패로(머리 줄 글자는 휴대폰에서 눈에 잘 안 띈다).
        <div className="tc-wish-tile" role="status" aria-label={`소원 ${wishLabel(tableView.activeWishRank)}`}>
          <small>소원</small>
          <b>{wishLabel(tableView.activeWishRank)}</b>
        </div>
      )}
      {lacquerTheme && (
        <ArenaFxLayer
          mySeat={mySeat}
          isInDealing={isInDealing}
          dealingCardCount={tableView.dealingCardCount}
          cardAnimEnabled={cardAnimEnabled}
        />
      )}
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
