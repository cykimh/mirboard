import { GameIcon } from '@/components/GameIcon';
import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { ErrorNotice } from '@/components/notice/FloatingNotice';
import { GameMenu } from '@/components/GameMenu';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useSfx } from '@/features/sound/useSfx';
import type { DambadukCell, DambadukDir, DambadukMoveOption, DambadukStoneKind } from '@/types/dambaduk';
import { dambadukRoomSink } from './dambadukRoomSink';
import { PENDING_GUARD_MS, useDambadukStore, type PendingAt } from './dambadukStore';
import { setDambadukSfxSeat } from './dambadukSfx';
import { DambadukBoard, KIND_NAME, type Destination } from './DambadukBoard';
import { DambadukMatchEnd, END_REASON_TEXT } from './DambadukMatchEnd';
import { DambadukTurnCountdown } from './DambadukTurnCountdown';
import { DIR_LABEL, cellIndex, cellName, faceOf, pathLabel } from './dambadukGeometry';
import { COLOR_KEY, COLOR_NAME, DAMBADUK_FX, DAMBADUK_TOKENS_CLASS } from './dambadukTheme';
import { StoneArt } from './dambadukArt';
import { DAMBADUK_TUTORIAL } from './tutorial/dambadukTutorial';

/** D-140 — 오목과 같은 멈춘 차례 복구 유예·지터. */
export const STALE_TURN_GRACE_MS = 2000;
export const STALE_TURN_JITTER_MS = 1000;
/** 종료 배너가 떠 있는 시간(화면 타이머 — 동작 줄이기에서도 같은 시간 뒤 사라진다). */
export const END_BANNER_MS = 2400;

const EXIT_BADGE = { TIMEOUT: '시간 초과로 빠짐', DESERTED: '나감' } as const;

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
  practice?: boolean;
  onAbort?: () => void;
  /** D-120 — 방은 끝났는데 이 세션이 종료 전이를 봐서 게임판을 유지하는 중. 입력을 숨긴다. */
  roomFinished?: boolean;
  /** D-153 — 판이 끝나 방이 대기실로 돌아왔다. [대기실로]가 부른다. */
  onBackToRoom?: () => void;
  /** D-162 — 방장만: 끊긴 사람을 내보낸다. */
  onKick?: (userId: number) => void;
  tiers?: Record<number, Tier>;
}

/** 같은 칸으로 가는 수끼리 묶는다(꺾은돌의 대각 칸은 길이 둘일 수 있다). */
function groupByDestination(options: DambadukMoveOption[]): Destination[] {
  const byCell = new Map<number, Destination>();
  for (const o of options) {
    const cell = { x: o.toX, y: o.toY };
    const idx = cellIndex(cell);
    const d = byCell.get(idx);
    if (d) d.options.push(o);
    else byCell.set(idx, { cell, options: [o] });
  }
  return Array.from(byCell.values());
}

/** 같은 칸으로 가는 길이 여럿일 때 서로 다른 결과를 내는 것만 — 담을 넘지 않는 길끼리는 결과가 같다(담 자리도 같다). */
function distinctRoutes(options: DambadukMoveOption[]): DambadukMoveOption[] {
  const seen = new Set<string>();
  return options.filter((o) => {
    const k = o.jump ? `j${o.jumpedFace}` : 'plain';
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/**
 * 담바둑 게임판 조립 루트 (D-279). 다른 게임판처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>조작: 배치 — 빈 칸을 누르면 흐린 돌, 한 번 더 누르거나 [여기에 놓기]로 확정(3인은 종류를 고른다). 옮김 — 내 돌 → 갈 수 있는 칸 →
 * 담 면(판의 손잡이나 아래 방향 버튼 넷)을 누르면 한 액션(`MOVE_STONE`)으로 보낸다. 확정 전에는 [다시 고르기]. 3인은 [담 넘기] 토글.
 * 갈 수 있는 칸·담 자리·집·점수는 서버가 보낸 값이다 — 클라는 판정하지 않는다.
 */
export function DambadukTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  usernames = {},
  turnSeconds = 0,
  spectatorCount = 0,
  onExit,
  onAbort,
  practice = false,
  roomFinished = false,
  onBackToRoom,
  onKick,
  tiers = {},
}: Props) {
  const token = useAuthStore((st) => st.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
    roomId,
    token,
    dambadukRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const { muted, toggleMute, playChime } = useSfx();
  const chatLines = useSeatChatLines(playerIds);
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(DAMBADUK_TUTORIAL.seenKey, false);

  const s = useDambadukStore();
  const seatCount = s.seatCount || playerIds.length;
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  const turnSeat = roomFinished ? -1 : s.turnSeat;
  const out = new Set(s.exits.map((e) => e.seat));
  const myTurn = !spectator && mySeat >= 0 && s.result === null && !roomFinished && turnSeat === mySeat;
  const canAct = myTurn && connected && s.pending === null;
  const placing = s.phase === 'PLACING';

  useEffect(() => {
    setDambadukSfxSeat(mySeat);
    return () => setDambadukSfxSeat(-1);
  }, [mySeat]);

  // 내 차례가 되면 알림음 + 휴대폰 진동 한 번(오목·원카드와 같다).
  const wasMyTurn = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !wasMyTurn.current) {
      playChime();
      if (!muted) navigator.vibrate?.(120);
    }
    wasMyTurn.current = myTurn;
  }, [myTurn, playChime, muted]);

  // 다시 받기 신호.
  const resyncNonce = s.resyncNonce;
  useEffect(() => {
    if (resyncNonce > 0) requestResync();
  }, [resyncNonce, requestResync]);

  // D-140 — 멈춘 차례 복구: 카운트다운이 0 + 유예 + 지터 뒤에도 같은 차례면 그 차례에 한 번 권위 스냅샷.
  const turnClock = s.turnClock;
  const turnEpoch = s.turnEpoch;
  const counting = !roomFinished && turnSeat >= 0 && turnSeconds > 0 && turnClock !== null;
  useEffect(() => {
    if (!counting || turnClock === null) return;
    const full = turnSeconds * 1000;
    const deadline = turnClock.since + Math.min(turnClock.remainingMs ?? full, full);
    const timer = window.setTimeout(
      () => {
        const st = useDambadukStore.getState();
        if (st.turnEpoch === turnEpoch && st.result === null) st.requestTurnResync(turnEpoch);
      },
      Math.max(0, deadline + STALE_TURN_GRACE_MS + Math.random() * STALE_TURN_JITTER_MS - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [counting, turnClock, turnEpoch, turnSeconds]);

  // 종료 배너 — 이 세션이 판이 끝나는 순간을 봤을 때만(끝난 판에 들어오면 현판만).
  const result = s.result;
  const hadResult = useRef(result !== null);
  const [endBanner, setEndBanner] = useState(false);
  useEffect(() => {
    if (result && !hadResult.current && DAMBADUK_FX === 'rich') setEndBanner(true);
    if (!result) setEndBanner(false);
    hadResult.current = result !== null;
  }, [result]);
  useEffect(() => {
    if (!endBanner) return;
    const t = window.setTimeout(() => setEndBanner(false), END_BANNER_MS);
    return () => window.clearTimeout(t);
  }, [endBanner]);

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };
  const colorOf = (seat: number) => s.seatColors[seat] ?? 0;

  // ── 배치 ──
  const placeKind: DambadukStoneKind | null =
    s.placeKind && s.placeKinds.includes(s.placeKind) ? s.placeKind : (s.placeKinds[0] ?? null);
  const occupied = useMemo(() => new Set(s.stones.map(cellIndex)), [s.stones]);

  /** 보내고, 보냈을 때만 응답 대기(흐린 수)를 건다 — 끊긴 사이에 누른 수는 대기를 걸지 않고 알린다(S3 리뷰). */
  const send = (action: Record<string, unknown>, onSent: (at: PendingAt) => void) => {
    const at = { moveCount: s.moveCount, stoneCount: s.stones.length, sentAt: Date.now() };
    if (sendAction(action) === false) {
      s.setError('연결이 끊겨 보내지 못했어요. 다시 연결되면 한 번 더 눌러 주세요.');
      return;
    }
    onSent(at);
  };

  const confirmPlace = (cell: DambadukCell) => {
    if (!canAct || !placing || !placeKind || occupied.has(cellIndex(cell))) return;
    send({ '@action': 'PLACE_STONE', kind: placeKind, x: cell.x, y: cell.y }, (at) =>
      s.markPending({ type: 'place', x: cell.x, y: cell.y, kind: placeKind, at }),
    );
  };

  // ── 옮김 ──
  const myOptions = myTurn && !placing ? s.options : [];
  const canJump = mySeat >= 0 && (s.jumpsLeft[mySeat] ?? 0) > 0 && myOptions.some((o) => o.jump);
  // 담을 넘어야만 옮길 수 있으면 넘기를 저절로 켠다(끈 채로는 고를 돌이 없다 — e2e 가 잡은 막힘).
  const jumpOnly = canJump && !myOptions.some((o) => !o.jump);
  const jumping = canJump && (s.jumpMode || jumpOnly);
  // 고를 수 있는 돌은 지금 모드(넘기 켬/끔)에서 갈 칸이 있는 돌뿐이다 — 눌렀는데 갈 칸이 하나도 없는 돌을 띄우지 않는다.
  const movable = useMemo(
    () => new Set(myOptions.filter((o) => o.jump === jumping).map((o) => o.stoneId)),
    [myOptions, jumping],
  );
  // 고른 돌이 지금 모드에서 갈 칸이 없으면 고르지 않은 것으로 본다(넘기 모드가 바뀐 뒤 '고름'으로 멈추지 않게 — S3 리뷰).
  const selectedId = s.selectedStoneId !== null && movable.has(s.selectedStoneId) ? s.selectedStoneId : null;
  const visibleOptions = myOptions.filter((o) => o.stoneId === selectedId && o.jump === jumping);
  const destinations = groupByDestination(visibleOptions);
  const routes = s.choice
    ? distinctRoutes(visibleOptions.filter((o) => o.toX === s.choice!.toX && o.toY === s.choice!.toY))
    : [];

  const onCell = (cell: DambadukCell) => {
    if (!canAct) return;
    if (placing) {
      if (occupied.has(cellIndex(cell))) return;
      if (s.placeCell && s.placeCell.x === cell.x && s.placeCell.y === cell.y) confirmPlace(cell);
      else s.setPlaceCell(cell);
      return;
    }
    const st = s.stones.find((x) => x.x === cell.x && x.y === cell.y);
    if (st && movable.has(st.id)) {
      s.selectStone(st.id === selectedId ? null : st.id);
      return;
    }
    const dest = destinations.find((d) => d.cell.x === cell.x && d.cell.y === cell.y);
    if (dest) {
      s.choose(dest.options[0]);
      return;
    }
    s.selectStone(null);
  };

  const sendMove = (option: DambadukMoveOption, wall: DambadukDir) => {
    if (!canAct) return;
    const to = { x: option.toX, y: option.toY };
    send({ '@action': 'MOVE_STONE', stoneId: option.stoneId, path: option.path, wall, jump: option.jump }, (at) =>
      s.markPending({
        type: 'move',
        stoneId: option.stoneId,
        toX: to.x,
        toY: to.y,
        wallFace: faceOf(to, wall),
        jumpedFace: option.jumpedFace,
        at,
      }),
    );
  };

  // 보낸 수가 PENDING_GUARD_MS 동안 소식이 없으면(반쯤 끊긴 망) 흐린 수를 풀고 권위 스냅샷으로 맞춘다 — 대기를 그대로 두면 canAct 가 꺼진 채
  // 시간 초과로 빠지거나, 턴 제한이 꺼진 방에서는 영원히 막힌다(S3 리뷰, 얏추 D-270 과 같은 생각).
  const pending = s.pending;
  useEffect(() => {
    if (!pending) return;
    const timer = window.setTimeout(
      () => {
        const st = useDambadukStore.getState();
        if (st.pending !== pending) return;
        st.clearPending();
        requestResync();
      },
      Math.max(0, pending.at.sentAt + PENDING_GUARD_MS - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [pending, requestResync]);

  const onWall = (dir: DambadukDir) => {
    if (s.choice && s.choice.walls.includes(dir)) sendMove(s.choice, dir);
  };

  const exit = () => {
    if (!onExit) return;
    if (s.result === null && !roomFinished && !spectator && mySeat >= 0 && !out.has(mySeat)) {
      const text =
        seatCount === 2 ? '판 도중에 나가면 집니다. 나갈까요?' : '판 도중에 나가면 내 돌이 치워지고 꼴찌가 됩니다. 나갈까요?';
      if (!window.confirm(text)) return;
    }
    onExit();
  };

  // ── 안내 줄 ──
  const selected = s.stones.find((x) => x.id === selectedId) ?? null;
  let status: string;
  if (s.result) status = END_REASON_TEXT[s.result.reason];
  else if (roomFinished) status = '게임이 종료되었습니다.';
  else if (turnSeat < 0) status = '다음 차례를 기다리는 중…';
  else if (myTurn && s.pending) status = '보내는 중…';
  else if (myTurn && placing && s.placeCell) status = `${cellName(s.placeCell)} — 한 번 더 누르거나 [여기에 놓기]`;
  else if (myTurn && placing) status = `내 차례 — ${placeKind ? KIND_NAME[placeKind] : '돌'}을 놓을 빈 칸을 누르세요`;
  else if (myTurn && s.choice) status = `${cellName({ x: s.choice.toX, y: s.choice.toY })} — 담을 세울 면을 고르세요`;
  else if (myTurn && selected)
    status = jumping ? '담을 넘어 갈 칸을 누르세요' : '갈 칸을 누르세요';
  else if (myTurn) status = '내 차례 — 옮길 돌을 누르세요';
  else status = `${nameOf(turnSeat)}(${COLOR_NAME[colorOf(turnSeat)]}) 차례`;

  // 건너뜀은 바로 다음 차례 동안만(스토어가 그다음 차례 넘김에 비운다).
  const skippedNote =
    s.skipped && !s.result ? `${s.skipped.seats.map(nameOf).join('·')} — 옮길 돌이 없어 건너뜀` : null;

  const seats = Array.from({ length: seatCount }, (_, i) => i);

  return (
    <div className={`db-table ${DAMBADUK_TOKENS_CLASS}`}>
      <header className="db-header gm-header">
        <div className="db-header-badges gm-header-info">
          <GameIcon gameId="DAMBADUK" size={22} />
          <span className="db-badge db-badge-title">담바둑</span>
          <span className="db-badge">{placing ? '돌 놓기' : s.phase === 'PLAYING' ? `${s.moveCount}수` : '끝'}</span>
          {spectator && <span className="db-badge">👀 관전 중</span>}
        </div>
        <div className="db-header-actions gm-header-actions">
          <button type="button" className="db-badge" onClick={() => setChatOpen((v) => !v)} aria-pressed={chatOpen}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <GameMenu
            triggerClassName="db-badge"
            info={[`${seatCount}인`, practice && '봇과 연습', spectatorCount > 0 && `관전 ${spectatorCount}명`]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onAbort={onAbort}
            onExit={onExit ? exit : undefined}
          />
        </div>
      </header>

      {/* 좌석 띠 — 차례 순서(좌석 +1). 점수는 "지금 그대로 끝나면"의 값(서버 계산). */}
      <ol className="db-strip" aria-label="차례 순서와 점수">
        {seats.map((seat) => {
          const uid = playerIds[seat];
          const isTurn = turnSeat === seat;
          const exit = s.exits.find((e) => e.seat === seat);
          const score = s.territory.scores[seat] ?? 0;
          const largest = s.territory.largest[seat] ?? 0;
          return (
            <li
              key={seat}
              className={`db-seat db-c-${COLOR_KEY[colorOf(seat)]}${isTurn ? ' db-seat-turn' : ''}${
                seat === mySeat ? ' db-seat-me' : ''
              }${exit ? ' db-seat-out' : ''}`}
              aria-current={isTurn ? 'step' : undefined}
            >
              <span className="db-seat-top">
                {/* 색 점은 그림이라 화면 읽기에는 숨긴 글자로 색을 읽힌다(결과표와 같다 — aria-label 만으로는 읽히지 않는다). */}
                <span className={`db-dot db-c-${COLOR_KEY[colorOf(seat)]}`} aria-hidden="true" />
                <span className="db-sr">{COLOR_NAME[colorOf(seat)]} </span>
                {tiers[uid] && <TierBadge tier={tiers[uid]} size={16} />}
                <span className="db-seat-name">
                  {nameOf(seat)}
                  {seat === mySeat ? ' (나)' : ''}
                </span>
                {isTurn && <DambadukTurnCountdown turnSeconds={turnSeconds} />}
              </span>
              <span className="db-seat-score">
                {placing ? (
                  `돌 ${s.stones.filter((x) => x.seat === seat).length}개`
                ) : (
                  <>
                    <span className="db-seat-home">
                      집 <b>{score}</b>칸
                    </span>
                    <span className="db-seat-sep">{' · '}</span>
                    <span className="db-seat-large">큰 집 {largest}</span>
                  </>
                )}
                {seatCount === 3 && !exit && (
                  <span className="db-seat-jump" title="담 넘기(한 판에 한 번)">
                    <span className="db-seat-sep">{' · '}</span>넘기 {s.jumpsLeft[seat] ?? 0}
                  </span>
                )}
              </span>
              {exit && <span className="db-badge db-badge-warn">{EXIT_BADGE[exit.reason]}</span>}
              {s.disconnectedSeats.has(seat) && !exit && <span className="db-badge db-badge-warn">끊김</span>}
              {onKick && !roomFinished && seat !== mySeat && s.disconnectedSeats.has(seat) && !exit && (
                <button type="button" className="db-badge" onClick={() => onKick(uid)}>
                  내보내기
                </button>
              )}
              {chatLines[seat] && (
                <span key={chatLines[seat].id} className="db-seat-chat" title={chatLines[seat].text}>
                  {chatLines[seat].text}
                </span>
              )}
            </li>
          );
        })}
      </ol>

      <div className="db-stage">
        {/* D-269 — 끊김·거절 안내는 화면 위에 뜬다(FloatingNotice). 게임판 흐름에 오류 줄을 끼우지 않는다. */}
        <ReconnectBanner connected={connected} />
        <ErrorNotice message={s.errorMessage} seq={s.errorSeq} sticky={s.errorSticky} onClose={() => s.setError(null)} />
        <div className={`db-board-wrap${myTurn ? ' db-board-myturn' : ''}${placing ? ' db-board-placing' : ''}`}>
          <DambadukBoard
            stones={s.stones}
            walls={s.walls}
            territory={s.territory}
            seatColors={s.seatColors}
            mySeat={mySeat}
            mode={canAct ? (placing ? 'place' : 'move') : 'none'}
            placeCell={canAct && placing ? s.placeCell : null}
            placeKind={placeKind}
            movableStoneIds={canAct ? movable : undefined}
            selectedStoneId={canAct ? selectedId : null}
            destinations={canAct ? destinations : []}
            choice={canAct ? s.choice : null}
            pendingPlace={s.pending?.type === 'place' ? { ...s.pending, seat: mySeat } : null}
            pendingMove={s.pending?.type === 'move' ? s.pending : null}
            lastWallFace={s.lastMove?.wallFace ?? -1}
            lastTrail={
              s.lastMove
                ? {
                    from: { x: s.lastMove.fromX, y: s.lastMove.fromY },
                    to: { x: s.lastMove.toX, y: s.lastMove.toY },
                    jumpedFace: s.lastMove.jumpedFace,
                  }
                : null
            }
            fxSeq={s.fxSeq}
            onCell={onCell}
            onWall={onWall}
          />
          {endBanner && s.result && (
            <div className="db-end-banner" aria-hidden="true" data-testid="db-end-banner">
              <span className="db-end-banner-text">
                {s.result.draw
                  ? '무승부'
                  : `${s.result.winners.map((w) => `${COLOR_NAME[colorOf(w)]}`).join('·')} 승`}
              </span>
              <span className="db-end-banner-who">
                {s.result.draw ? END_REASON_TEXT[s.result.reason] : s.result.winners.map(nameOf).join(' · ')}
              </span>
            </div>
          )}
        </div>
      </div>

      <p className={`db-status${myTurn ? ' db-status-mine' : ''}`} aria-live="polite">
        {status}
        {skippedNote && <span className="db-status-note"> · {skippedNote}</span>}
      </p>

      {canAct && placing && (
        <div className="db-controls">
          {s.placeKinds.length > 0 && seatCount === 3 && (
            <div className="db-kind-pick" role="group" aria-label="놓을 돌">
              {(['STRAIGHT', 'BENT'] as const).map((k) => (
                <button
                  key={k}
                  type="button"
                  className={`db-kind db-kind-${k === 'STRAIGHT' ? 'straight' : 'bent'} db-c-${COLOR_KEY[colorOf(mySeat)]}`}
                  aria-pressed={placeKind === k}
                  disabled={!s.placeKinds.includes(k)}
                  onClick={() => s.setPlaceKind(k)}
                >
                  <span className="db-kind-art" aria-hidden="true">
                    <StoneArt kind={k} />
                  </span>
                  {KIND_NAME[k]}
                </button>
              ))}
            </div>
          )}
          <button
            type="button"
            className="db-primary"
            disabled={!s.placeCell}
            onClick={() => s.placeCell && confirmPlace(s.placeCell)}
          >
            여기에 놓기
          </button>
        </div>
      )}

      {canAct && !placing && (
        <div className="db-controls">
          {canJump && (
            <button
              type="button"
              className={`db-jump${jumping ? ' db-jump-on' : ''}`}
              aria-pressed={jumping}
              disabled={jumpOnly}
              onClick={() => s.setJumpMode(!s.jumpMode)}
            >
              담 넘기 {jumpOnly ? '— 넘어야만 옮길 수 있어요' : jumping ? '켜짐' : '(한 번 남음)'}
            </button>
          )}
          {s.choice && routes.length > 1 && (
            <div className="db-routes" role="group" aria-label="어느 길로 갈까요">
              {routes.map((r) => (
                <button
                  key={`${r.path.join('-')}`}
                  type="button"
                  className="db-route"
                  aria-pressed={s.choice === r}
                  onClick={() => s.choose(r)}
                >
                  {pathLabel(r.path)}
                </button>
              ))}
            </div>
          )}
          {s.choice && (
            <div className="db-wall-pad" role="group" aria-label="담을 세울 면">
              {(['UP', 'LEFT', 'RIGHT', 'DOWN'] as const).map((d) => (
                <button
                  key={d}
                  type="button"
                  className={`db-wall-btn db-wall-btn-${d.toLowerCase()}`}
                  disabled={!s.choice!.walls.includes(d)}
                  onClick={() => onWall(d)}
                >
                  {DIR_LABEL[d]} 담
                </button>
              ))}
              <span className={`db-wall-pad-center db-c-${COLOR_KEY[colorOf(mySeat)]}`} aria-hidden="true">
                <StoneArt kind={selected?.kind ?? 'STRAIGHT'} />
              </span>
            </div>
          )}
          {(selectedId !== null || s.choice) && (
            <button type="button" className="db-secondary" onClick={() => s.selectStone(null)}>
              다시 고르기
            </button>
          )}
        </div>
      )}

      {s.result && (
        <DambadukMatchEnd
          result={s.result}
          seatColors={s.seatColors}
          mySeat={mySeat}
          nameOf={nameOf}
          onExit={onExit}
          onBackToRoom={onBackToRoom}
        />
      )}

      {roomFinished && !s.result && (
        <section className="db-end" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="db-primary" onClick={onExit}>
              메인으로
            </button>
          )}
        </section>
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

      <TutorialDialog tutorial={DAMBADUK_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
