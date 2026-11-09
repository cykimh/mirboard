import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
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
import { ISLAND_NAME, KIND_HINT, LEVEL_NAME, LETTER_TEXT, TILES, gridOf, partnerOf } from './board';
import { cloudrouteRoomSink } from './cloudrouteRoomSink';
import { useCloudRouteStore } from './cloudrouteStore';
import { describe, g } from './describe';
import { CLOUD_ROUTE_TUTORIAL } from './tutorial/cloudrouteTutorial';

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

const SEAT_ICON = ['🎈', '🪁', '🛸', '🦋'];

/**
 * 구름항로 게임판 조립 루트 (D-213). 원카드처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103). 내 좌석은 비공개 뷰가 없으므로
 * 방의 좌석 목록에서 찾는다(D-212 — 모든 정보가 공개라 관전자도 같은 판을 본다, 결정 패널만 없다).
 *
 * <p>판은 7×7 테두리 격자({@link gridOf})이고 가운데 칸에 주사위·라운드·사건 줄이 놓인다. 결정은 바닥의 결정 패널 하나다 — 축제·비행은
 * 판의 후보 칸을 눌러 고르고, 그 밖에는 칸을 누르면 칸 시트(이름·주인·통행료표)가 열린다.
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

  const send = (action: CloudRouteAction) => {
    if (!connected) return;
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

  const lines = s.log
    .map((entry) => ({ id: entry.id, text: describe(entry, nameOf) }))
    .filter((x): x is { id: number; text: string } => x.text !== null);
  const latest = lines.length > 0 ? lines[lines.length - 1].text : '';

  return (
    <div className="cr-table">
      <header className="cr-header">
        <div className="cr-header-badges">
          <span className="cr-badge cr-badge-title">구름항로</span>
          <span className="cr-badge">{seatCount}인</span>
          {s.roundLimit > 0 && (
            <span className="cr-badge">
              라운드 {s.round}/{s.roundLimit}
            </span>
          )}
          {practice && <span className="cr-badge">🤖 혼자 연습</span>}
          {turnSeconds > 0 && <span className="cr-badge">턴 {turnSeconds}초</span>}
          {spectatorCount > 0 && <span className="cr-badge">관전 {spectatorCount}</span>}
          {spectator && <span className="cr-badge">관전 모드</span>}
          <span className="cr-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="cr-header-badges">
          <button
            type="button"
            className="cr-badge"
            onClick={toggleMute}
            aria-label="효과음"
            aria-pressed={!muted}
            title={muted ? '효과음 켜기' : '효과음 끄기'}
          >
            {muted ? '🔇' : '🔊'}
          </button>
          <button type="button" className="cr-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button type="button" className="cr-badge" onClick={rules.show} title="게임 방법 — 턴 타이머는 계속 흐릅니다">
            규칙
          </button>
          {onAbort && !roomFinished && (
            <button type="button" className="cr-badge" onClick={onAbort}>
              게임 종료
            </button>
          )}
          {onExit && (
            <button type="button" className="cr-badge" onClick={exit}>
              나가기
            </button>
          )}
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <p className="cr-error" role="alert">
          <span>{s.errorMessage}</span>
          <button type="button" className="cr-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
            ×
          </button>
        </p>
      )}

      <ol className="cr-seats" aria-label="좌석">
        {s.seats.map((seat) => (
          <SeatChip
            key={seat.seat}
            seat={seat}
            name={nameOf(seat.seat)}
            me={seat.seat === mySeat}
            turn={turnSeat === seat.seat}
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

      <div className={`cr-board${myTurn ? ' cr-board-myturn' : ''}`} role="group" aria-label="판">
        {TILES.map((info) => {
          const view = s.tiles.find((t) => t.tile === info.id);
          const { row, col } = gridOf(info.id);
          const here = s.seats.filter((x) => !x.eliminated && x.position === info.id);
          return (
            <TileButton
              key={info.id}
              tile={info.id}
              view={view}
              row={row}
              col={col}
              festival={s.festivalTile === info.id}
              pickable={pickable.has(info.id)}
              tokens={here.map((x) => x.seat)}
              ownerName={view && view.owner >= 0 ? nameOf(view.owner) : null}
              onClick={() => onTile(info.id)}
            />
          );
        })}
        <div className="cr-center">
          <div className="cr-center-top">
            {counting && <TurnCountdown turnSeconds={turnSeconds} />}
            {s.lastDice.length === 2 && (
              <span className="cr-dice" aria-label={`주사위 ${s.lastDice[0]}, ${s.lastDice[1]}`}>
                <span className="cr-die">{s.lastDice[0]}</span>
                <span className="cr-die">{s.lastDice[1]}</span>
              </span>
            )}
          </div>
          <p className="cr-center-turn">
            {s.result ? '판 종료' : turnSeat >= 0 ? `${nameOf(turnSeat)}의 차례` : ''}
          </p>
          {s.lastLetter && (
            <p className="cr-letter" title={LETTER_TEXT[s.lastLetter].effect}>
              ✉ {LETTER_TEXT[s.lastLetter].name}
            </p>
          )}
          <ul className="cr-log" aria-label="방금 일어난 일">
            {lines.slice(-3).map((line) => (
              <li key={line.id}>{line.text}</li>
            ))}
          </ul>
          <p className="cr-deck">편지 {s.deckCount}장</p>
        </div>
      </div>

      {s.result && (
        <MatchEnd result={s.result} mySeat={mySeat} nameOf={nameOf} onExit={onExit} onBackToRoom={onBackToRoom} />
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

function SeatChip({
  seat,
  name,
  me,
  turn,
  disconnected,
  tier,
  onKick,
}: {
  seat: CloudRouteSeatView;
  name: string;
  me: boolean;
  turn: boolean;
  disconnected: boolean;
  tier?: Tier;
  onKick?: () => void;
}) {
  return (
    <li
      className={`cr-seat cr-seat-${seat.seat}${turn ? ' cr-seat-turn' : ''}${seat.eliminated ? ' cr-seat-out' : ''}`}
      aria-current={turn ? 'true' : undefined}
    >
      <span className="cr-seat-name">
        <span aria-hidden="true">{SEAT_ICON[seat.seat]}</span>
        {tier && <TierBadge tier={tier} size={14} />}
        <strong>{name}</strong>
        {me && <span className="cr-seat-me">(나)</span>}
      </span>
      <span className="cr-seat-cash">{seat.eliminated ? '—' : g(seat.cash)}</span>
      <span className="cr-seat-tags">
        {turn && <span className="cr-tag cr-tag-turn">차례</span>}
        {seat.trapped && <span className="cr-tag">🌀 갇힘</span>}
        {seat.holdsBuoy && <span className="cr-tag">🛟 부표</span>}
        {seat.eliminated && <span className="cr-tag">{seat.eliminated === 'BANKRUPT' ? '파산' : '탈주'}</span>}
        {disconnected && <span className="cr-tag">끊김</span>}
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
  pickable,
  tokens,
  ownerName,
  onClick,
}: {
  tile: number;
  view?: CloudRouteTileView;
  row: number;
  col: number;
  festival: boolean;
  pickable: boolean;
  tokens: number[];
  ownerName: string | null;
  onClick: () => void;
}) {
  const info = TILES[tile];
  const owner = view?.owner ?? -1;
  const lvl = view?.level ?? 0;
  const label = [
    info.name,
    ownerName ? `${ownerName}의 땅 ${LEVEL_NAME[lvl]}` : info.kind === 'LAND' ? `빈 땅 ${g(info.price)}` : null,
    festival ? '축제 중' : null,
    tokens.length > 0 ? `말 ${tokens.length}` : null,
    pickable ? '고를 수 있음' : null,
  ]
    .filter(Boolean)
    .join(', ');
  return (
    <button
      type="button"
      className={`cr-tile cr-kind-${info.kind.toLowerCase()}${owner >= 0 ? ` cr-owned cr-owner-${owner}` : ''}${
        pickable ? ' cr-pickable' : ''
      }`}
      data-island={info.island ?? undefined}
      style={{ gridRow: row + 1, gridColumn: col + 1 }}
      onClick={onClick}
      aria-label={label}
    >
      <span className="cr-tile-band" aria-hidden="true" />
      <span className="cr-tile-name">{info.kind === 'LAND' ? info.name : KIND_ICON[info.kind]}</span>
      {/* 아랫줄 — 말·축제·주인. 이름과 겹치지 않게 칸 바닥에 한 줄로 둔다. */}
      <span className="cr-tile-foot" aria-hidden="true">
        <span className="cr-tokens">
          {festival && <span className="cr-tile-festival">🏮</span>}
          {tokens.map((seat) => (
            <span key={seat} className={`cr-token cr-token-${seat}`}>
              {SEAT_ICON[seat]}
            </span>
          ))}
        </span>
        {owner >= 0 && (
          <span className="cr-tile-owner">
            {owner + 1}
            {lvl > 0 && <span className="cr-tile-level">{lvl === 3 ? '🗼' : '•'.repeat(lvl)}</span>}
          </span>
        )}
      </span>
    </button>
  );
}

const KIND_ICON: Record<string, string> = {
  START: '⚓ 출발',
  LETTER: '✉ 편지',
  WHIRLPOOL: '🌀',
  CUSTOMS: '🛃 세관',
  FESTIVAL: '🏮 축제',
  STATION: '🎐 정류장',
};

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
                🎲 굴리기
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
                🎲 굴리기
              </button>
              {pending.canPay && (
                <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'PAY_ESCAPE' })}>
                  50G 내고 나가기
                </button>
              )}
              {pending.canUseBuoy && (
                <button type="button" className="cr-btn" disabled={disabled} onClick={() => send({ '@action': 'USE_BUOY' })}>
                  🛟 구명 부표
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
            <LevelPicker from={0} to={pending.maxLevel} value={chosen} onChange={setLevel} />
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
            <LevelPicker from={pending.minLevel} to={pending.maxLevel} value={chosen} onChange={setLevel} />
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
                  🏮 {TILES[tile].name}
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
                  🎁 {TILES[tile].name}
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
  onChange,
}: {
  from: number;
  to: number;
  value: number;
  onChange: (level: number) => void;
}) {
  const options = [];
  for (let lvl = from; lvl <= to; lvl++) options.push(lvl);
  return (
    <div className="cr-levels" role="radiogroup" aria-label="건설 단계">
      {options.map((lvl) => (
        <button
          key={lvl}
          type="button"
          role="radio"
          aria-checked={value === lvl}
          className={`cr-level${value === lvl ? ' cr-level-on' : ''}`}
          onClick={() => onChange(lvl)}
        >
          {LEVEL_NAME[lvl]}
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
        <strong>{info.name}</strong>
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
                  <th scope="row">{name}</th>
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
  const title = mine?.rank === 1 ? (winners > 1 ? '공동 1위 🎉' : '승리 🎉') : '판 종료';
  return (
    <section className="match-end cr-match-end" aria-label="판 종료">
      <h3>{title}</h3>
      <p className="cr-match-reason">
        {REASON_TEXT[result.reason]} ({result.rounds}라운드)
      </p>
      <ol className="cr-standings">
        {standings.map((x) => (
          <li key={x.seat} className={x.seat === mySeat ? 'cr-standing-me' : undefined}>
            <span className="cr-standing-rank">{x.rank}위</span>
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
      ⏱ {seconds}초
    </span>
  );
}

