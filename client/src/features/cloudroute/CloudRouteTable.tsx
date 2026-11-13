import { GameIcon } from '@/components/GameIcon';
import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { FloatingNotice } from '@/components/notice/FloatingNotice';
import { GameMenu } from '@/components/GameMenu';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useSfx } from '@/features/sound/useSfx';
import type {
  CloudRouteAction,
  CloudRouteMatchResult,
  CloudRoutePending,
  CloudRouteSeatView,
  CloudRouteTileView,
  EndReason,
} from '@/types/cloudroute';
import { Balloon, Building, CardBack, DieFace, Icon, LetterArt, TileArt } from './art';
import { ISLAND_NAME, KIND_HINT, LEVEL_NAME, LETTER_TEXT, TILES, gridOf, partnerOf, tollOf } from './board';
import { cloudrouteRoomSink } from './cloudrouteRoomSink';
import { useCloudRouteStore } from './cloudrouteStore';
import { describe, g } from './describe';
import { burst, confettiFall } from './fxParticles';
import { skyPhaseOf, type SkyPhase } from './sky';
import { CLOUD_ROUTE_FX_LEVEL, CLOUD_ROUTE_THEME } from './theme';
import { tokenPoint } from './tokenLayout';
import { CLOUD_ROUTE_TUTORIAL } from './tutorial/cloudrouteTutorial';
import { useCloudRouteFx, type CardFx, type TokenFx } from './useCloudRouteFx';

/** D-140 방식 — 카운트다운이 0 이 된 뒤 이만큼(+ 지터) 지나도 같은 차례면 권위 스냅샷을 한 번 다시 받는다. */
export const STALE_TURN_GRACE_MS = 2000;
export const STALE_TURN_JITTER_MS = 1000;

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
  roomFinished?: boolean;
  onBackToRoom?: () => void;
  onKick?: (userId: number) => void;
  tiers?: Record<number, Tier>;
}

/** 움직임 연출을 틀 수 있는 환경인가 — 그림책 테마 + 움직임 줄이기 아님 + Web Animations 가 있음(jsdom 에는 없다). */
function motionAllowed(): boolean {
  if (CLOUD_ROUTE_THEME !== 'storybook') return false;
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  if (typeof Element === 'undefined' || typeof Element.prototype.animate !== 'function') return false;
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * 구름항로 게임판 조립 루트 (D-213). 원카드처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103). 내 좌석은 비공개 뷰가 없으므로
 * 방의 좌석 목록에서 찾는다(D-212 — 모든 정보가 공개라 관전자도 같은 판을 본다, 결정 패널만 없다).
 *
 * <p>판은 7×7 테두리 격자({@link gridOf})이고 가운데 칸에 주사위·라운드·사건 줄이 놓인다. 결정은 결정 패널 하나다 — 축제·비행은
 * 판의 후보 칸을 눌러 고르고, 그 밖에는 칸을 누르면 칸 시트(이름·주인·통행료표)가 열린다.
 *
 * <p>D-218 — 그림책 테마에서는 연출 감독({@link useCloudRouteFx})이 **보여 주는 값**(말 위치·현금·칸 주인·사건 줄)을 한 걸음씩 따라
 * 그린다. 결정 패널·차례 판정·타이머는 늘 스토어(진짜 값)를 본다 — 연출이 조작을 늦추지 않는다.
 */
export function CloudRouteTable({
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
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
    roomId,
    token,
    cloudrouteRoomSink,
  );
  const s = useCloudRouteStore();
  const [chatOpen, setChatOpen] = useState(false);
  const [sheetTile, setSheetTile] = useState<number | null>(null);
  const [level, setLevel] = useState<number | null>(null);
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(CLOUD_ROUTE_TUTORIAL.seenKey, false);
  const { muted, toggleMute, playChime } = useSfx();

  const seatCount = s.seats.length || playerIds.length;
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  const me = s.seats.find((x) => x.seat === mySeat) ?? null;
  const iAmOut = me?.eliminated != null;
  const turnSeat = roomFinished ? -1 : s.turnSeat;
  const myTurn = mySeat >= 0 && !iAmOut && s.result === null && !roomFinished && turnSeat === mySeat;
  const pending = myTurn ? s.pending : null;

  // ── 연출(D-218) — 보여 주는 값은 감독이 붙잡은 것, 없으면 스토어 그대로. ──
  const rootRef = useRef<HTMLDivElement>(null);
  const boardRef = useRef<HTMLDivElement>(null);
  const [motion] = useState(motionAllowed);
  const fx = useCloudRouteFx(rootRef, { enabled: motion, hurry: pending !== null, mySeat });
  const shown = fx.view;
  const seats = shown?.seats ?? s.seats;
  const tiles = shown?.tiles ?? s.tiles;
  const festivalTile = shown ? shown.festivalTile : s.festivalTile;
  const lastDice = shown?.lastDice ?? s.lastDice;
  const lastLetter = shown ? shown.lastLetter : s.lastLetter;
  const shownTurn = roomFinished ? -1 : shown ? shown.turnSeat : s.turnSeat;
  const shownRound = shown?.round ?? s.round;
  const deckCount = shown?.deckCount ?? s.deckCount;
  const logEntries = shown?.log ?? s.log;
  // 결과 화면은 마지막 연출(파산 등)이 끝난 뒤에 올린다.
  const result = s.result && !fx.busy ? s.result : null;
  // 하늘 시간대(D-219) — 보여 주는 라운드를 따라 아침 → 낮 → 노을 → 밤. 화면 전용.
  const skyPhase = skyPhaseOf(shownRound, s.roundLimit);

  // 판 크기 단위 — 판 폭의 1/100 을 `--cr-u` 로 심는다(말 자리·글자 크기가 이것을 쓴다. 시안의 container query 대신).
  useLayoutEffect(() => {
    const board = boardRef.current;
    if (!board) return;
    const apply = () => {
      const width = board.clientWidth;
      if (width > 0) rootRef.current?.style.setProperty('--cr-u', `${(width / 100).toFixed(3)}px`);
    };
    apply();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(apply);
    observer.observe(board);
    return () => observer.disconnect();
  }, []);

  // 구름 층 패럴랙스 — 마우스가 있는 화면에서만, 프레임당 한 번 CSS 변수만 바꾼다.
  useEffect(() => {
    const board = boardRef.current;
    if (!motion || !board || !window.matchMedia('(hover: hover)').matches) return;
    let raf = 0;
    const onMove = (e: PointerEvent) => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        const r = board.getBoundingClientRect();
        board.style.setProperty('--cr-px', (((e.clientX - r.left) / r.width) * 2 - 1).toFixed(3));
        board.style.setProperty('--cr-py', (((e.clientY - r.top) / r.height) * 2 - 1).toFixed(3));
      });
    };
    board.addEventListener('pointermove', onMove);
    return () => {
      board.removeEventListener('pointermove', onMove);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [motion]);

  // 결과 축포 — 결과 화면이 올라올 때 한 번.
  const cheered = useRef(false);
  useEffect(() => {
    if (!result) {
      cheered.current = false;
      return;
    }
    if (!motion || cheered.current) return;
    cheered.current = true;
    const root = rootRef.current;
    const layer = root?.querySelector<HTMLElement>('.cr-fx');
    const box = root?.querySelector<HTMLElement>('.cr-match-end');
    if (!root || !layer || !box) return;
    box.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
    const timers = [0, 380, 760, 1300].map((delay, i) =>
      window.setTimeout(() => {
        const a = box.getBoundingClientRect();
        const b = layer.getBoundingClientRect();
        const x = a.left - b.left + a.width * [0.5, 0.12, 0.88, 0.5][i];
        burst(layer, { x, y: a.top - b.top + a.height * (i === 0 || i === 3 ? 0.3 : 0.55) }, 'cheer', Math.min(1.1, a.width / 380));
      }, delay),
    );
    if (CLOUD_ROUTE_FX_LEVEL >= 2) {
      // 종이 꽃가루 비 — 축포 사이로 결과 상자 위에서 두 번 내린다.
      for (const delay of [200, 1500]) {
        timers.push(
          window.setTimeout(() => {
            const a = box.getBoundingClientRect();
            const b = layer.getBoundingClientRect();
            confettiFall(layer, a.left - b.left, a.right - b.left, Math.max(0, a.top - b.top - 40), Math.min(a.height + 60, 420), 22);
          }, delay),
        );
      }
    }
    return () => timers.forEach((t) => window.clearTimeout(t));
  }, [result, motion]);

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };

  // 결정이 바뀌면 고른 단계는 그 결정의 가장 높은 단계로 다시 맞춘다.
  // 내 결정이 새로 오면 칸 시트를 닫는다 — 바닥 시트가 결정 패널을 덮지 않게.
  const pendingKey = JSON.stringify(s.pending);
  useEffect(() => {
    setLevel(null);
    if (myTurn) setSheetTile(null);
  }, [pendingKey, myTurn]);

  // 내 차례가 되면 알림음 + 휴대폰 진동 한 번(원카드 D-160 과 같은 약속).
  const wasMyTurn = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !wasMyTurn.current) {
      playChime();
      if (!muted) navigator.vibrate?.(120);
    }
    wasMyTurn.current = myTurn;
  }, [myTurn, playChime, muted]);

  // 멈춘 차례 복구 — 카운트다운 0 + 유예 + 지터 뒤에도 같은 차례면 그 차례에 한 번 resync.
  const turnClock = s.turnClock;
  const turnEpoch = s.turnEpoch;
  const counting = !roomFinished && turnSeat >= 0 && turnSeconds > 0 && turnClock !== null;
  useEffect(() => {
    if (!counting || turnClock === null) return;
    const full = turnSeconds * 1000;
    const deadline = turnClock.since + Math.min(turnClock.remainingMs ?? full, full);
    const jitter = Math.random() * STALE_TURN_JITTER_MS;
    const timer = window.setTimeout(() => {
      const current = useCloudRouteStore.getState();
      if (current.turnEpoch === turnEpoch) current.requestTurnResync(turnEpoch);
    }, Math.max(0, deadline + STALE_TURN_GRACE_MS + jitter - Date.now()));
    return () => window.clearTimeout(timer);
  }, [counting, turnClock, turnEpoch, turnSeconds]);

  const handledResync = useRef(s.resyncNonce);
  useEffect(() => {
    if (s.resyncNonce === handledResync.current) return;
    handledResync.current = s.resyncNonce;
    if (s.resyncNonce !== 0) requestResync();
  }, [s.resyncNonce, requestResync]);

  const skipFx = fx.skip;
  const send = (action: CloudRouteAction) => {
    if (!connected) return;
    // 내가 결정을 보냈다 — 아직 돌고 있는 연출은 건너뛰고 진짜 값으로(연출이 조작을 늦추지 않는다).
    skipFx();
    sendAction(action);
  };

  const exit = () => {
    if (!onExit) return;
    if (
      !spectator &&
      s.result === null &&
      !roomFinished &&
      !iAmOut &&
      !window.confirm('게임 중에 나가면 탈주로 처리되어 최하위가 됩니다. 나가시겠습니까?')
    ) {
      return;
    }
    onExit();
  };

  // 축제·비행 결정 중에는 판의 후보 칸을 눌러 고른다.
  const pickable = new Set(
    pending?.kind === 'FESTIVAL' || pending?.kind === 'FLIGHT' || pending?.kind === 'GIFT' || pending?.kind === 'SALE'
      ? pending.candidates
      : [],
  );
  const onTile = (tile: number) => {
    if (pickable.has(tile) && pending) {
      send(
        pending.kind === 'FESTIVAL'
          ? { '@action': 'HOST_FESTIVAL', tile }
          : pending.kind === 'GIFT'
            ? { '@action': 'GIFT_BUILD', tile }
            : pending.kind === 'SALE'
              ? { '@action': 'SELL', tile }
              : { '@action': 'FLY', tile },
      );
      return;
    }
    setSheetTile(tile);
  };

  const lines = logEntries
    .map((entry) => ({ id: entry.id, text: describe(entry, nameOf) }))
    .filter((x): x is { id: number; text: string } => x.text !== null);
  const latest = lines.length > 0 ? lines[lines.length - 1].text : '';

  const ownerOf = (tile: number) => tiles.find((t) => t.tile === tile)?.owner ?? -1;
  const standing = seats.filter((x) => !x.eliminated);

  return (
    <div
      ref={rootRef}
      className={`cr-table cr-${CLOUD_ROUTE_THEME}${CLOUD_ROUTE_FX_LEVEL >= 2 ? ' cr-fx2' : ''}${motion ? ' cr-motion' : ''}`}
      data-cr-sky={skyPhase}
    >
      {/* 하늘빛 층(D-219) — 시간대마다 겹쳐 둔 색 층의 투명도만 바꾼다. 장식. */}
      <div className="cr-daylight" aria-hidden="true">
        <span className="cr-daylight-tint cr-daylight-morning" />
        <span className="cr-daylight-tint cr-daylight-sunset" />
        <span className="cr-daylight-tint cr-daylight-night" />
      </div>
      <header className="cr-header gm-header">
        <div className="cr-header-badges gm-header-info">
          <GameIcon gameId="CLOUD_ROUTE" size={22} />
          <span className="cr-badge cr-badge-title">구름항로</span>
          {s.roundLimit > 0 && (
            <span className="cr-badge">
              라운드 {shownRound}/{s.roundLimit}
            </span>
          )}
          {spectator && <span className="cr-badge">관전 모드</span>}
        </div>
        <div className="cr-header-badges gm-header-actions">
          <button type="button" className="cr-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          {/* D-186 — 방 정보·설정·규칙·게임 종료·나가기는 ⋮ 메뉴로(머리 줄 한 줄 고정). */}
          <GameMenu
            triggerClassName="cr-badge"
            info={[
              `${seatCount}인`,
              practice && '봇과 연습',
              turnSeconds > 0 && `턴 ${turnSeconds}초`,
              spectatorCount > 0 && `관전 ${spectatorCount}명`,
              connected ? '연결됨' : '연결 끊김',
            ]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onAbort={onAbort && !roomFinished ? onAbort : undefined}
            onExit={onExit ? exit : undefined}
          />
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <FloatingNotice tone="error" onClose={() => s.setError(null)}>
          {s.errorMessage}
        </FloatingNotice>
      )}

      <div className="cr-layout">
        <div className="cr-board-wrap">
          <div ref={boardRef} className={`cr-board${myTurn ? ' cr-board-myturn' : ''}`} role="group" aria-label="판">
            <Sky phase={skyPhase} />
            {TILES.map((info) => {
              const view = tiles.find((t) => t.tile === info.id);
              const { row, col } = gridOf(info.id);
              const owner = view?.owner ?? -1;
              return (
                <TileButton
                  key={info.id}
                  tile={info.id}
                  view={view}
                  row={row}
                  col={col}
                  festival={festivalTile === info.id}
                  monopoly={owner >= 0 && info.kind === 'LAND' && ownerOf(partnerOf(info.id)) === owner}
                  pickable={pickable.has(info.id)}
                  tokenCount={standing.filter((x) => (fx.tokens[x.seat]?.pos ?? x.position) === info.id).length}
                  ownerName={owner >= 0 ? nameOf(owner) : null}
                  onClick={() => onTile(info.id)}
                />
              );
            })}
            <div className="cr-center">
              <div className="cr-center-top">
                {counting && <TurnCountdown turnSeconds={turnSeconds} />}
                {lastDice.length === 2 && (
                  <span className="cr-dice" aria-label={`주사위 ${lastDice[0]}, ${lastDice[1]}`}>
                    <Die value={lastDice[0]} roll={fx.dice.n} ms={fx.dice.ms} />
                    <Die value={lastDice[1]} roll={fx.dice.n} ms={fx.dice.ms} />
                  </span>
                )}
              </div>
              <p className="cr-center-turn">
                {s.result ? '판 종료' : shownTurn >= 0 ? `${nameOf(shownTurn)}의 차례` : ''}
              </p>
              {lastLetter && (
                <p className="cr-letter" title={LETTER_TEXT[lastLetter].effect}>
                  <Icon name="envelope" /> {LETTER_TEXT[lastLetter].name}
                </p>
              )}
              <ul className="cr-log" aria-label="방금 일어난 일">
                {lines.slice(-3).map((line) => (
                  <li key={line.id}>{line.text}</li>
                ))}
              </ul>
              <p className="cr-deck">
                <span className="cr-deck-pile" aria-hidden="true">
                  <CardBack />
                </span>
                <span>편지 {deckCount}장</span>
              </p>
            </div>
            {fx.route && (
              <svg
                key={fx.route.n}
                className="cr-route"
                viewBox="0 0 100 100"
                aria-hidden="true"
                style={{ '--cr-ms': `${fx.route.ms}ms` } as CSSProperties}
              >
                {ROUTE_TAIL.map((color, i) => (
                  <path
                    key={color}
                    className="cr-route-tail"
                    d={fx.route!.d}
                    pathLength={100}
                    stroke={color}
                    strokeWidth={3.4 - i * 0.62}
                  />
                ))}
                <path className="cr-route-dots" d={fx.route.d} pathLength={100} />
              </svg>
            )}
            <div className="cr-tokens" aria-hidden="true">
              {standing.map((seat) => (
                <TokenPiece
                  key={seat.seat}
                  seat={seat.seat}
                  fx={fx.tokens[seat.seat]}
                  tile={fx.tokens[seat.seat]?.pos ?? seat.position}
                  peers={standing
                    .filter((x) => fx.tokens[x.seat]?.pos === undefined && x.position === seat.position)
                    .map((x) => x.seat)}
                  turn={shownTurn === seat.seat}
                  me={seat.seat === mySeat}
                />
              ))}
            </div>
            {fx.card && <LetterCard card={fx.card} who={nameOf(fx.card.seat)} />}
          </div>
        </div>

        <div className="cr-side">
          <ol className="cr-seats" aria-label="좌석">
            {seats.map((seat) => (
              <SeatChip
                key={seat.seat}
                seat={seat}
                name={nameOf(seat.seat)}
                me={seat.seat === mySeat}
                turn={shownTurn === seat.seat}
                lands={tiles.filter((t) => t.owner === seat.seat).length}
                animate={motion}
                disconnected={s.disconnectedSeats.has(seat.seat)}
                tier={tiers[playerIds[seat.seat]]}
                onKick={
                  onKick && !roomFinished && seat.seat !== mySeat && s.disconnectedSeats.has(seat.seat)
                    ? () => onKick(playerIds[seat.seat])
                    : undefined
                }
              />
            ))}
          </ol>

          {result && (
            <MatchEnd result={result} mySeat={mySeat} nameOf={nameOf} onExit={onExit} onBackToRoom={onBackToRoom} />
          )}
          {roomFinished && !s.result && (
            <section className="cr-finished-note" aria-label="게임 종료">
              <p>게임이 종료되었습니다.</p>
              {onExit && (
                <button type="button" className="cr-btn" onClick={exit}>
                  메인으로
                </button>
              )}
            </section>
          )}

          {!s.result && !roomFinished && (
            <DecisionPanel
              pending={pending}
              waitingOn={turnSeat >= 0 && !myTurn ? nameOf(turnSeat) : null}
              othersPending={!myTurn ? s.pending : null}
              me={me}
              tiles={s.tiles}
              doubles={s.doublesThisTurn}
              level={level}
              setLevel={setLevel}
              ownerName={(seat) => nameOf(seat)}
              disabled={!connected}
              send={send}
            />
          )}
        </div>
      </div>

      {sheetTile !== null && (
        <TileSheet
          tile={sheetTile}
          view={s.tiles.find((t) => t.tile === sheetTile)}
          festival={s.festivalTile === sheetTile}
          ownerName={(seat) => nameOf(seat)}
          tiles={s.tiles}
          onClose={() => setSheetTile(null)}
        />
      )}

      <div className="cr-sr-only" aria-live="polite" aria-atomic="true">
        {latest}
      </div>

      {/* 연출 입자 레이어(D-218) — 꽃가루·동전·말풍선이 잠깐 산다. 누를 수 없고 보조기기에는 없다. */}
      <div className="cr-fx" aria-hidden="true" />

      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
          onClose={() => setChatOpen(false)}
          roomId={roomId}
        />
      )}
      <TutorialDialog tutorial={CLOUD_ROUTE_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}

const ROUTE_TAIL = ['#ff9aa2', '#ffd59e', '#fff3a6', '#b5ead7', '#bcd4ff'];

/** 가운데 하늘 — 무지개와 세 겹 구름(깊이마다 다른 속도로 흐르고, 마우스를 따라 살짝 어긋난다). 장식. */
function Sky({ phase }: { phase: SkyPhase }) {
  return (
    <div className={`cr-sky cr-sky-${phase}`} aria-hidden="true">
      {/* 해와 달(D-219) — 시간대마다 자리를 옮기고 서로 바뀐다. */}
      <div className="cr-sky-orb">
        <svg className="cr-sky-sun" viewBox="0 0 60 60">
          <g className="cr-sky-rays" strokeWidth="3.4" strokeLinecap="round">
            {[0, 45, 90, 135, 180, 225, 270, 315].map((deg) => (
              <path key={deg} d="M30 5v7" transform={`rotate(${deg} 30 30)`} />
            ))}
          </g>
          <circle className="cr-sky-sun-disc" cx="30" cy="30" r="13" />
          <path className="cr-sky-sun-face" d="M25 29.500q1 -1.600 2 0M33 29.500q1 -1.600 2 0M26.500 34q3.500 2.600 7 0" />
        </svg>
        <svg className="cr-sky-moon" viewBox="0 0 60 60">
          <path className="cr-sky-moon-disc" d="M39 10a21 21 0 1 0 11 29a17 17 0 0 1 -11 -29Z" />
          <path className="cr-sky-moon-face" d="M24 30q1.200 1.600 2.400 0M27 37q3 2 5.600 -.400" />
        </svg>
      </div>
      <svg className="cr-sky-layer cr-sky-far" viewBox="0 0 700 700" preserveAspectRatio="none">
        <g className="cr-sky-stars">
          {SKY_STARS.map(([x, y, r], i) => (
            <circle key={i} cx={x} cy={y} r={r} />
          ))}
        </g>
        <g className="cr-sky-rainbow" fill="none" strokeWidth="15">
          <path d="M160 480a190 190 0 0 1 380 0" stroke="#ff9aa2" />
          <path d="M175 480a175 175 0 0 1 350 0" stroke="#ffd59e" />
          <path d="M190 480a160 160 0 0 1 320 0" stroke="#fff3a6" />
          <path d="M205 480a145 145 0 0 1 290 0" stroke="#b5ead7" />
          <path d="M220 480a130 130 0 0 1 260 0" stroke="#bcd4ff" />
        </g>
      </svg>
      <svg className="cr-sky-layer cr-sky-mid" viewBox="0 0 700 700" preserveAspectRatio="none">
        <g className="cr-cloud cr-cloud-a">
          <ellipse cx="470" cy="170" rx="50" ry="16" />
          <circle cx="455" cy="158" r="20" />
          <circle cx="488" cy="154" r="24" />
        </g>
        <g className="cr-cloud cr-cloud-b">
          <ellipse cx="200" cy="205" rx="40" ry="13" />
          <circle cx="190" cy="195" r="16" />
          <circle cx="214" cy="191" r="19" />
        </g>
        <g className="cr-cloud cr-cloud-c">
          <ellipse cx="350" cy="560" rx="46" ry="14" />
          <circle cx="336" cy="549" r="18" />
          <circle cx="366" cy="545" r="21" />
        </g>
        {/* 판을 가로질러 흐르는 구름 두 덩이(D-219). */}
        <g className="cr-cloud cr-cloud-flow cr-cloud-flow-a">
          <ellipse cx="0" cy="262" rx="44" ry="13" />
          <circle cx="-12" cy="252" r="17" />
          <circle cx="14" cy="248" r="21" />
        </g>
        <g className="cr-cloud cr-cloud-flow cr-cloud-flow-b">
          <ellipse cx="0" cy="424" rx="34" ry="10" />
          <circle cx="-9" cy="416" r="13" />
          <circle cx="11" cy="413" r="16" />
        </g>
      </svg>
      {/* 가끔 지나가는 새 떼(D-219) — 낮에만. */}
      <svg className="cr-sky-layer cr-sky-birds" viewBox="0 0 700 700" preserveAspectRatio="none">
        <g className="cr-flock">
          {SKY_BIRDS.map(([x, y, k], i) => (
            <g key={i} transform={`translate(${x} ${y}) scale(${k})`}>
              <path className="cr-bird" d="M-11 0q5.500 -8 11 0q5.500 -8 11 0" />
            </g>
          ))}
        </g>
      </svg>
      <svg className="cr-sky-layer cr-sky-near" viewBox="0 0 700 700" preserveAspectRatio="none">
        <g className="cr-cloud cr-cloud-b">
          <ellipse cx="175" cy="492" rx="78" ry="26" />
          <circle cx="140" cy="474" r="30" />
          <circle cx="192" cy="462" r="38" />
        </g>
        <g className="cr-cloud cr-cloud-a">
          <ellipse cx="525" cy="492" rx="78" ry="26" />
          <circle cx="505" cy="462" r="38" />
          <circle cx="560" cy="476" r="28" />
        </g>
      </svg>
    </div>
  );
}

const SKY_BIRDS: [number, number, number][] = [
  [0, 200, 1],
  [-34, 216, 0.8],
  [-30, 184, 0.85],
  [-70, 204, 0.7],
];

const SKY_STARS: [number, number, number][] = [
  [150, 150, 3],
  [250, 120, 2],
  [330, 180, 2.5],
  [560, 230, 3],
  [600, 140, 2],
  [130, 330, 2],
  [580, 380, 2.5],
  [300, 290, 2],
  [420, 250, 3],
  [520, 560, 2],
  [180, 580, 2.5],
];

/** 주사위 — 그림책 테마는 여섯 면 입체(굴릴 때마다 `roll` 이 올라 다시 구른다), 기본 테마는 한 면. */
function Die({ value, roll, ms }: { value: number; roll: number; ms: number }) {
  if (CLOUD_ROUTE_THEME !== 'storybook') {
    return (
      <span className="cr-die">
        <DieFace value={value} />
      </span>
    );
  }
  return (
    <span className="cr-die cr-die-3d">
      <span key={`t${roll}`} className={`cr-die-toss${roll > 0 ? ' cr-die-tossing' : ''}`} style={{ animationDuration: `${ms}ms` }}>
        <span
          key={roll}
          className={`cr-cube cr-cube-v${value}${roll > 0 ? ' cr-cube-roll' : ''}`}
          style={{ animationDuration: `${ms}ms` }}
        >
          {[1, 2, 3, 4, 5, 6].map((face) => (
            <span key={face} className={`cr-cube-face cr-cube-f${face}`}>
              <DieFace value={face} />
            </span>
          ))}
        </span>
      </span>
      <span key={`s${roll}`} className={`cr-die-shadow${roll > 0 ? ' cr-die-shadow-toss' : ''}`} style={{ animationDuration: `${ms}ms` }} />
    </span>
  );
}

/** 말 하나 — 자리는 판 단위(`--cr-u`) transform 으로 옮긴다. 몸(포물선·찌그러짐)과 그림자는 따로 움직인다. */
function TokenPiece({
  seat,
  fx,
  tile,
  peers,
  turn,
  me,
}: {
  seat: number;
  fx?: TokenFx;
  tile: number;
  peers: number[];
  turn: boolean;
  me: boolean;
}) {
  const moving = fx?.pos !== undefined;
  const index = Math.max(0, peers.indexOf(seat));
  const p = moving ? tokenPoint(tile) : tokenPoint(tile, index, peers.length);
  const mode = fx?.mode ?? 'idle';
  const ms = fx?.ms ?? 220;
  const style = {
    transform: `translate(calc(var(--cr-u) * ${p.x.toFixed(2)} - 50%), calc(var(--cr-u) * ${p.y.toFixed(2)} - 50%))`,
    transitionDuration: `${mode === 'hop' || mode === 'idle' ? ms : mode === 'land' ? ms : 0}ms`,
    '--cr-ms': `${Math.max(ms, 1)}ms`,
  } as CSSProperties;
  return (
    <span
      className={`cr-token cr-c-${seat}${moving ? ' cr-token-moving' : ''}${turn ? ' cr-token-turn' : ''}${me ? ' cr-token-me' : ''}`}
      data-cr-token={seat}
      style={style}
    >
      <span key={`s${fx?.n ?? 0}`} className={`cr-token-shadow cr-token-shadow-${mode}`} />
      <span key={`b${fx?.n ?? 0}`} className={`cr-token-body cr-token-${mode}`}>
        <Balloon seat={seat} />
      </span>
    </span>
  );
}

/** 바람 편지 한 장 — 더미에서 날아와 입체로 뒤집히고 빛살이 돈다. 장식(문장은 사건 줄·알림이 전한다). */
function LetterCard({ card, who }: { card: CardFx; who: string }) {
  const text = LETTER_TEXT[card.letter];
  return (
    <div className="cr-cardwrap" aria-hidden="true">
      <div key={card.n} className={`cr-card cr-card-${card.phase}`}>
        <span className="cr-card-rays" />
        <div className="cr-card-flipper">
          <div className="cr-card-face cr-card-back">
            <CardBack />
          </div>
          <div className="cr-card-face cr-card-front">
            <span className="cr-card-kicker">{who}의 바람 편지</span>
            <LetterArt letter={card.letter} />
            <b className="cr-card-name">{text.name}</b>
            <span className="cr-card-effect">{text.effect}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 현금 숫자 — 연출이 켜져 있으면 바뀔 때 0.45초 동안 굴러간다. */
function Cash({ value, animate }: { value: number; animate: boolean }) {
  const [shown, setShown] = useState(value);
  const from = useRef(value);
  useEffect(() => {
    if (!animate || from.current === value) {
      from.current = value;
      setShown(value);
      return;
    }
    const start = from.current;
    const t0 = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const k = Math.min(1, (now - t0) / 450);
      const eased = 1 - (1 - k) * (1 - k);
      const next = Math.round(start + (value - start) * eased);
      from.current = next;
      setShown(next);
      if (k < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value, animate]);
  return <>{g(animate ? shown : value)}</>;
}

function SeatChip({
  seat,
  name,
  me,
  turn,
  lands,
  animate,
  disconnected,
  tier,
  onKick,
}: {
  seat: CloudRouteSeatView;
  name: string;
  me: boolean;
  turn: boolean;
  lands: number;
  animate: boolean;
  disconnected: boolean;
  tier?: Tier;
  onKick?: () => void;
}) {
  return (
    <li
      className={`cr-seat cr-seat-${seat.seat} cr-c-${seat.seat}${turn ? ' cr-seat-turn' : ''}${seat.eliminated ? ' cr-seat-out' : ''}`}
      aria-current={turn ? 'true' : undefined}
      data-cr-seat={seat.seat}
    >
      <span className="cr-seat-avatar" aria-hidden="true">
        <Balloon seat={seat.seat} />
      </span>
      <span className="cr-seat-name">
        {tier && <TierBadge tier={tier} size={14} />}
        <strong>{name}</strong>
        {me && <span className="cr-seat-me">(나)</span>}
      </span>
      <span className="cr-seat-cash">
        {seat.eliminated ? (
          '—'
        ) : (
          <>
            <Icon name="coin" />
            <Cash value={seat.cash} animate={animate} />
          </>
        )}
      </span>
      <span className="cr-seat-tags">
        {!seat.eliminated && <span className="cr-seat-lands">땅 {lands}</span>}
        {turn && <span className="cr-tag cr-tag-turn">차례</span>}
        {seat.trapped && (
          <span className="cr-tag">
            <Icon name="swirl" /> 갇힘
          </span>
        )}
        {seat.holdsBuoy && (
          <span className="cr-tag">
            <Icon name="buoy" /> 부표
          </span>
        )}
        {seat.eliminated && <span className="cr-tag cr-tag-bad">{seat.eliminated === 'BANKRUPT' ? '파산' : '탈주'}</span>}
        {disconnected && <span className="cr-tag cr-tag-bad">끊김</span>}
        {onKick && (
          <button type="button" className="cr-tag cr-kick" onClick={onKick}>
            내보내기
          </button>
        )}
      </span>
    </li>
  );
}

function TileButton({
  tile,
  view,
  row,
  col,
  festival,
  monopoly,
  pickable,
  tokenCount,
  ownerName,
  onClick,
}: {
  tile: number;
  view?: CloudRouteTileView;
  row: number;
  col: number;
  festival: boolean;
  monopoly: boolean;
  pickable: boolean;
  tokenCount: number;
  ownerName: string | null;
  onClick: () => void;
}) {
  const info = TILES[tile];
  const owner = view?.owner ?? -1;
  const lvl = view?.level ?? 0;
  const land = info.kind === 'LAND';
  const toll = owner >= 0 ? tollOf(tile, lvl, monopoly, festival) : 0;
  const label = [
    info.name,
    ownerName ? `${ownerName}의 땅 ${LEVEL_NAME[lvl]}` : land ? `빈 땅 ${g(info.price)}` : null,
    festival ? '축제 중' : null,
    tokenCount > 0 ? `말 ${tokenCount}` : null,
    pickable ? '고를 수 있음' : null,
    ownerName ? `통행료 ${g(toll)}` : null,
  ]
    .filter(Boolean)
    .join(', ');
  const corner = (row === 0 || row === 6) && (col === 0 || col === 6);
  return (
    <button
      type="button"
      className={`cr-tile cr-kind-${info.kind.toLowerCase()}${owner >= 0 ? ` cr-owned cr-owner-${owner} cr-c-${owner}` : ''}${
        pickable ? ' cr-pickable' : ''
      }${festival ? ' cr-fest' : ''}${corner ? ` cr-corner cr-corner-${row === 0 ? 't' : 'b'}${col === 0 ? 'l' : 'r'}` : ''}`}
      data-island={info.island ?? undefined}
      data-cr-tile={tile}
      style={{ gridRow: row + 1, gridColumn: col + 1 }}
      onClick={onClick}
      aria-label={label}
    >
      <span className="cr-tile-band" aria-hidden="true" />
      <span className="cr-tile-art" aria-hidden="true">
        <TileArt kind={info.kind} island={info.island} />
      </span>
      <span className="cr-tile-name">{info.name}</span>
      {land && (
        <span className="cr-tile-foot" aria-hidden="true">
          {owner >= 0 ? (
            <span className="cr-tile-owner">
              <b className="cr-tile-seatno">{owner + 1}</b>
              {monopoly && <Icon name="star" />}
              <span className="cr-tile-toll">{toll}</span>
            </span>
          ) : (
            <span className="cr-tile-price">{g(info.price)}</span>
          )}
        </span>
      )}
      {owner >= 0 && (
        <span className="cr-tile-bld" aria-hidden="true">
          <Building key={lvl} level={lvl} />
        </span>
      )}
      {festival && (
        <span className="cr-tile-festival" aria-hidden="true">
          <Icon name="lantern" />
        </span>
      )}
    </button>
  );
}

function DecisionPanel({
  pending,
  waitingOn,
  othersPending,
  me,
  tiles,
  doubles,
  level,
  setLevel,
  ownerName,
  disabled,
  send,
}: {
  pending: CloudRoutePending | null;
  waitingOn: string | null;
  othersPending: CloudRoutePending | null;
  me: CloudRouteSeatView | null;
  tiles: CloudRouteTileView[];
  doubles: number;
  level: number | null;
  setLevel: (level: number) => void;
  ownerName: (seat: number) => string;
  disabled: boolean;
  send: (action: CloudRouteAction) => void;
}) {
  if (!pending) {
    return (
      <section className="cr-panel cr-panel-wait" aria-label="결정">
        <p>{waitingOn ? `${waitingOn} — ${PENDING_TEXT[othersPending?.kind ?? 'ROLL']}` : ''}</p>
      </section>
    );
  }
  const cash = me?.cash ?? 0;
  const body = (() => {
    switch (pending.kind) {
      case 'ROLL':
        return (
          <>
            <p>{doubles > 0 ? `더블! 한 번 더 굴립니다 (${doubles}/2)` : '주사위를 굴리세요'}</p>
            <div className="cr-panel-actions">
              <button type="button" className="cr-btn cr-btn-main" disabled={disabled} onClick={() => send({ '@action': 'ROLL' })}>
                <Icon name="die" /> 굴리기
              </button>
            </div>
          </>
        );
      case 'WHIRLPOOL':
        return (
          <>
            <p>소용돌이에 갇혔습니다 — 더블이 나오면 나와서 그만큼 갑니다.</p>
            <div className="cr-panel-actions">
              <button type="button" className="cr-btn cr-btn-main" disabled={disabled} onClick={() => send({ '@action': 'ROLL' })}>
                <Icon name="die" /> 굴리기
              </button>
              {pending.canPay && (
                <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'PAY_ESCAPE' })}>
                  50G 내고 나가기
                </button>
              )}
              {pending.canUseBuoy && (
                <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'USE_BUOY' })}>
                  <Icon name="buoy" /> 구명 부표
                </button>
              )}
            </div>
          </>
        );
      case 'PURCHASE': {
        const info = TILES[pending.tile];
        const chosen = level ?? pending.maxLevel;
        const total = info.price + chosen * info.buildCost;
        return (
          <>
            <p>
              <strong>{info.name}</strong> · {g(info.price)} — 같이 지을 단계를 고르세요(현금 {g(cash)})
            </p>
            <LevelPicker seat={me?.seat ?? 0} from={0} to={pending.maxLevel} value={chosen} onChange={setLevel} />
            <div className="cr-panel-actions">
              <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'DECLINE' })}>
                안 사기
              </button>
              <button
                type="button"
                className="cr-btn cr-btn-main"
                disabled={disabled}
                onClick={() => send({ '@action': 'BUY', buildTo: chosen })}
              >
                사기 {g(total)}
              </button>
            </div>
          </>
        );
      }
      case 'BUILD': {
        const info = TILES[pending.tile];
        const chosen = level ?? pending.maxLevel;
        const cost = (chosen - pending.minLevel + 1) * info.buildCost;
        return (
          <>
            <p>
              내 땅 <strong>{info.name}</strong> — 더 지을까요?(현금 {g(cash)})
            </p>
            <LevelPicker seat={me?.seat ?? 0} from={pending.minLevel} to={pending.maxLevel} value={chosen} onChange={setLevel} />
            <div className="cr-panel-actions">
              <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'SKIP' })}>
                건너뛰기
              </button>
              <button
                type="button"
                className="cr-btn cr-btn-main"
                disabled={disabled}
                onClick={() => send({ '@action': 'BUILD', buildTo: chosen })}
              >
                {LEVEL_NAME[chosen]}까지 {g(cost)}
              </button>
            </div>
          </>
        );
      }
      case 'TAKEOVER':
        return (
          <>
            <p>
              {ownerName(pending.owner)}의 <strong>{TILES[pending.tile].name}</strong>을(를) {g(pending.price)}에 인수할까요?
              (주인은 거절할 수 없습니다)
            </p>
            <div className="cr-panel-actions">
              <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'SKIP' })}>
                안 함
              </button>
              <button type="button" className="cr-btn cr-btn-main" disabled={disabled} onClick={() => send({ '@action': 'TAKEOVER' })}>
                인수 {g(pending.price)}
              </button>
            </div>
          </>
        );
      case 'FESTIVAL':
        return (
          <>
            <p>축제를 열 내 땅을 판에서 누르세요 — 그 땅 통행료가 두 배가 됩니다.</p>
            <div className="cr-panel-actions">
              {pending.candidates.map((tile) => (
                <button
                  key={tile}
                  type="button"
                  className="cr-btn"
                  disabled={disabled}
                  onClick={() => send({ '@action': 'HOST_FESTIVAL', tile })}
                >
                  <Icon name="lantern" /> {TILES[tile].name}
                </button>
              ))}
              <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'SKIP' })}>
                건너뛰기
              </button>
            </div>
          </>
        );
      case 'SALE': {
        const to = pending.creditor >= 0 ? `${ownerName(pending.creditor)}에게` : '은행에';
        return (
          <>
            <p>
              {to} <strong>{g(pending.debt)}</strong>을(를) 내야 하는데 현금이 {g(cash)}입니다 — 팔 땅을 고르세요(반값, 건물이 있으면 한 단계씩).
            </p>
            <div className="cr-panel-actions">
              {pending.candidates.map((tile) => {
                const lvl = tiles.find((t) => t.tile === tile)?.level ?? 0;
                const info = TILES[tile];
                const unit = lvl > 0 ? `${LEVEL_NAME[lvl]} 한 단계` : '땅';
                const got = lvl > 0 ? Math.floor(info.buildCost / 2) : Math.floor(info.price / 2);
                return (
                  <button
                    key={tile}
                    type="button"
                    className="cr-btn"
                    disabled={disabled}
                    onClick={() => send({ '@action': 'SELL', tile })}
                  >
                    {info.name} {unit} +{g(got)}
                  </button>
                );
              })}
            </div>
          </>
        );
      }
      case 'GIFT':
        return (
          <>
            <p>등불 선물 — 1단계 무료로 올릴 내 땅을 판에서 누르세요(풍등탑은 선물로 세울 수 없어요).</p>
            <div className="cr-panel-actions">
              {pending.candidates.map((tile) => (
                <button
                  key={tile}
                  type="button"
                  className="cr-btn"
                  disabled={disabled}
                  onClick={() => send({ '@action': 'GIFT_BUILD', tile })}
                >
                  <Icon name="gift" /> {TILES[tile].name}
                </button>
              ))}
            </div>
          </>
        );
      case 'FLIGHT':
        return (
          <>
            <p>
              {pending.free
                ? '정류장 무료권 — 날아갈 칸을 판에서 누르세요(무료, 출발 항구를 지나면 급여).'
                : '50G 를 내고 날아갈 칸을 판에서 누르세요(출발 항구를 지나면 급여).'}
            </p>
            <div className="cr-panel-actions">
              <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'SKIP' })}>
                안 날기
              </button>
            </div>
          </>
        );
    }
  })();
  return (
    <section className="cr-panel cr-panel-mine" aria-label="내 결정">
      {body}
    </section>
  );
}

const PENDING_TEXT: Record<CloudRoutePending['kind'], string> = {
  ROLL: '굴리는 중',
  WHIRLPOOL: '소용돌이에서 나오는 중',
  PURCHASE: '살지 고르는 중',
  BUILD: '지을지 고르는 중',
  TAKEOVER: '인수할지 고르는 중',
  FESTIVAL: '축제 땅 고르는 중',
  FLIGHT: '날아갈 곳 고르는 중',
  GIFT: '선물할 땅 고르는 중',
  SALE: '팔 땅 고르는 중',
};

function LevelPicker({
  from,
  to,
  value,
  seat,
  onChange,
}: {
  seat: number;
  from: number;
  to: number;
  value: number;
  onChange: (level: number) => void;
}) {
  const options = [];
  for (let lvl = from; lvl <= to; lvl++) options.push(lvl);
  return (
    <div className={`cr-levels cr-c-${seat}`} role="radiogroup" aria-label="건설 단계">
      {options.map((lvl) => (
        <button
          key={lvl}
          type="button"
          role="radio"
          aria-checked={value === lvl}
          className={`cr-level${value === lvl ? ' cr-level-on' : ''}`}
          onClick={() => onChange(lvl)}
        >
          <Building level={lvl} />
          <span>{LEVEL_NAME[lvl]}</span>
        </button>
      ))}
    </div>
  );
}

function TileSheet({
  tile,
  view,
  festival,
  ownerName,
  tiles,
  onClose,
}: {
  tile: number;
  view?: CloudRouteTileView;
  festival: boolean;
  ownerName: (seat: number) => string;
  tiles: CloudRouteTileView[];
  onClose: () => void;
}) {
  const info = TILES[tile];
  const owner = view?.owner ?? -1;
  const partner = info.kind === 'LAND' ? partnerOf(tile) : -1;
  const monopoly = owner >= 0 && tiles.find((t) => t.tile === partner)?.owner === owner;
  return (
    <section className="cr-sheet" role="dialog" aria-label={`${info.name} 자세히`}>
      <header className="cr-sheet-head">
        <strong className="cr-sheet-title">
          <span className="cr-sheet-art" data-island={info.island ?? undefined} aria-hidden="true">
            <TileArt kind={info.kind} island={info.island} />
          </span>
          {info.name}
        </strong>
        <button type="button" className="cr-error-close" aria-label="닫기" onClick={onClose}>
          ×
        </button>
      </header>
      {info.kind === 'LAND' ? (
        <>
          <p>
            {info.island && ISLAND_NAME[info.island]} · 땅값 {g(info.price)} · 단계당 {g(info.buildCost)}
          </p>
          <p>
            {owner >= 0 ? `${ownerName(owner)}의 땅 — ${LEVEL_NAME[view?.level ?? 0]}` : '주인 없음'}
            {monopoly && ' · 섬 독점 ×1.5'}
            {festival && ' · 축제 ×2'}
          </p>
          <table className="cr-sheet-tolls">
            <tbody>
              {LEVEL_NAME.map((name, lvl) => (
                <tr key={name} className={owner >= 0 && view?.level === lvl ? 'cr-sheet-now' : undefined}>
                  <th scope="row" className={owner >= 0 ? `cr-c-${owner}` : 'cr-c-none'}>
                    <Building level={lvl} />
                    {name}
                  </th>
                  <td>{g(info.tolls[lvl])}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : (
        <p>{KIND_HINT[info.kind]}</p>
      )}
    </section>
  );
}

const WIN_RAINBOW = ['#ff9aa2', '#ffd59e', '#fff3a6', '#b5ead7', '#bcd4ff'];

const REASON_TEXT: Record<EndReason, string> = {
  ROUND_LIMIT: '마지막 라운드가 끝나 총자산으로 순위를 매겼습니다.',
  LAST_STANDING: '한 명만 남아 판이 끝났습니다.',
  NO_HUMANS: '남은 사람이 없어 판이 끝났습니다.',
};

function MatchEnd({
  result,
  mySeat,
  nameOf,
  onExit,
  onBackToRoom,
}: {
  result: CloudRouteMatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  onBackToRoom?: () => void;
}) {
  const standings = [...result.standings].sort((a, b) => a.rank - b.rank || a.seat - b.seat);
  const mine = standings.find((x) => x.seat === mySeat);
  const winners = standings.filter((x) => x.rank === 1).length;
  const title = mine?.rank === 1 ? (winners > 1 ? '공동 1위!' : '승리!') : '판 종료';
  return (
    <section className="match-end cr-match-end" aria-label="판 종료">
      {/* 우승 무지개(D-219) — 결과 상자 위로 한 줄씩 그려진다. 장식. */}
      <svg className="cr-win-rainbow" viewBox="0 0 200 100" aria-hidden="true">
        {WIN_RAINBOW.map((color, i) => (
          <path key={color} d={`M${14 + i * 8} 100a${86 - i * 8} ${86 - i * 8} 0 0 1 ${172 - i * 16} 0`} pathLength={100} stroke={color} />
        ))}
      </svg>
      <p className="cr-match-kicker">오늘의 하늘 산책 끝!</p>
      <h3>{title}</h3>
      <p className="cr-match-reason">
        {REASON_TEXT[result.reason]} ({result.rounds}라운드)
      </p>
      <ol className="cr-standings">
        {standings.map((x) => (
          <li
            key={x.seat}
            className={`cr-standing cr-c-${x.seat}${x.seat === mySeat ? ' cr-standing-me' : ''}${
              x.status === 'ALIVE' ? '' : ' cr-standing-out'
            }${x.rank === 1 ? ' cr-standing-first' : ''}`}
          >
            <span className="cr-standing-rank">{x.rank}위</span>
            <span className="cr-standing-avatar" aria-hidden="true">
              <Balloon seat={x.seat} />
            </span>
            <span className="cr-standing-name">{nameOf(x.seat)}</span>
            <span className="cr-standing-assets">
              {x.status === 'ALIVE' ? `총자산 ${g(x.totalAssets)}` : x.status === 'BANKRUPT' ? '파산' : '탈주'}
            </span>
          </li>
        ))}
      </ol>
      <div className="cr-panel-actions">
        {onBackToRoom && (
          <button type="button" className="cr-btn cr-btn-main" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className="cr-btn" onClick={onExit}>
            메인으로
          </button>
        )}
      </div>
    </section>
  );
}

function TurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const clock = useCloudRouteStore((st) => st.turnClock);
  const full = turnSeconds * 1000;
  const deadline = clock !== null ? clock.since + Math.min(clock.remainingMs ?? full, full) : null;
  // 마지막 틱의 시각과 그때의 기준. 기준이 바뀐 첫 그리기는 지난 틱 시각이 아니라 지금 시각으로 센다 — 낡은 시각이면 턴 제한보다 큰
  // 값('31초')이 한 프레임 보인다(원카드 OneCardTurnCountdown 과 같은 문제).
  const [tick, setTick] = useState(() => ({ deadline, now: Date.now() }));
  useEffect(() => {
    if (deadline === null) return;
    const run = () => setTick({ deadline, now: Date.now() });
    run();
    const timer = window.setInterval(run, 500);
    return () => window.clearInterval(timer);
  }, [deadline]);
  if (deadline === null) return null;
  const now = tick.deadline === deadline ? tick.now : Date.now();
  const seconds = Math.min(turnSeconds, Math.ceil(Math.max(0, deadline - now) / 1000));
  return (
    <span
      className={`cr-badge cr-countdown${seconds <= 5 ? ' cr-countdown-urgent' : ''}`}
      role="timer"
      aria-live="off"
      aria-label={`이번 결정 남은 시간 ${seconds}초`}
    >
      <Icon name="clock" /> {seconds}초
    </span>
  );
}

