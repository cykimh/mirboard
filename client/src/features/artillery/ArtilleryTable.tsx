import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { REACTIONS, useLiveReactions } from '@/features/chat/reactionStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { useSfx } from '@/features/sound/useSfx';
import { useVoiceChat, voiceStatusOf } from '@/features/voice/useVoiceChat';
import { VoiceControls } from '@/features/voice/VoiceControls';
import { FUEL, MAX_MOVE, type Shell, type ShotReport } from '@/types/artillery';
import { artilleryRoomSink } from './artilleryRoomSink';
import { useArtilleryStore } from './artilleryStore';
import { ArtilleryField } from './ArtilleryField';
import { ArtilleryControls } from './ArtilleryControls';
import { ArtilleryCountdown } from './ArtilleryCountdown';
import { ArtilleryMatchEnd } from './ArtilleryMatchEnd';
import { seatColor } from './draw';
import { walk } from './terrain';
import { ARTILLERY_TUTORIAL } from './tutorial/artilleryTutorial';

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

const OUT_LABEL: Record<string, string> = { KNOCKED_OUT: '시듦', CLOUD_SEA: '추락', DESERTED: '탈주' };

/** 대본 → 한 줄 낭독("보라의 호박탄 — 민수 28 피해, 지수 추락"). */
export function shotSummary(r: ShotReport, nameOf: (seat: number) => string): string {
  const parts: string[] = [];
  for (const d of r.damages) parts.push(`${nameOf(d.seat)} ${d.amount} 피해${d.direct ? '(직격)' : ''}`);
  for (const f of r.falls) if (!f.intoCloud && f.damage > 0) parts.push(`${nameOf(f.seat)} 낙하 ${f.damage}`);
  for (const e of r.eliminated) parts.push(`${nameOf(e.seat)} ${OUT_LABEL[e.cause] ?? '탈락'}`);
  const what = r.shot === null ? '발을 헛디뎠다' : r.shell === 'PUMPKIN' ? '호박탄' : '콩알';
  const result = parts.length > 0 ? parts.join(', ') : r.shot?.end === 'OUT' ? '전장 밖으로' : r.shot?.end === 'CLOUD' ? '구름 속으로' : '빗나감';
  return `${nameOf(r.seat)}의 ${what} — ${result}`;
}

/**
 * 씨앗포 게임판 조립 루트 (S3, 설계 `docs/plans/artillery.md` §8). 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>화면 중심은 Canvas 전장이다. 서버 대본을 재생하는 동안은 아무의 차례도 아니고, 내 차례에는 전장을 끌어 각도를, 조작부로 힘·탄·이동을
 * 정한다. 이동은 미리보기만 하다가 발사와 함께 한 번에 보낸다(D-220 — 차례당 액션 하나).
 */
export function ArtilleryTable({
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
  const { connected, sendAction, sendChat, sendReaction, sendVoice, subscribeVoice, chatPanelOpenRef } = useStompRoom(
    roomId,
    token,
    artilleryRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const { muted, toggleMute, playChime } = useSfx();
  const [reactionOpen, setReactionOpen] = useState(false);
  const liveReactions = useLiveReactions();
  const chatLines = useSeatChatLines(playerIds);
  const voice = useVoiceChat({ myUserId, connected, send: sendVoice, subscribe: subscribeVoice });
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(ARTILLERY_TUTORIAL.seenKey, false);
  const animate = useCardAnimStore((st) => st.enabled);
  const reducedMotion =
    !animate || (typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true);

  const s = useArtilleryStore();
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  const me = s.units.find((u) => u.seat === mySeat) ?? null;
  const turnSeat = roomFinished ? -1 : s.turnSeat;
  const myTurn =
    !spectator && mySeat >= 0 && !!me?.alive && s.result === null && s.phase === 'AIMING' && turnSeat === mySeat;
  const canAct = myTurn && connected && !s.pending && s.replay === null;

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };
  const names = useMemo(() => {
    const out: Record<number, string> = {};
    playerIds.forEach((_, seat) => {
      out[seat] = nameOf(seat);
    });
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playerIds, usernames, botSeats]);

  // 조준 상태(이 화면에만) — 차례마다 이동은 처음으로, 각도·힘은 지난 값을 둔다.
  const [angle, setAngleRaw] = useState(45);
  const angleTouched = useRef(false);
  const setAngle = (a: number) => {
    angleTouched.current = true;
    setAngleRaw(a);
  };
  const [power, setPower] = useState(55);
  const [shell, setShell] = useState<Shell>('PEA');
  const [moveDx, setMoveDx] = useState(0);
  const [lastPower, setLastPower] = useState<number | null>(null);
  const [lastAngles, setLastAngles] = useState<Record<number, number>>({});
  const turnKey = `${s.turnSeat}:${s.turnNo}`;
  useEffect(() => {
    setMoveDx(0);
  }, [turnKey]);
  useEffect(() => {
    if (me && me.pumpkins <= 0 && shell === 'PUMPKIN') setShell('PEA');
  }, [me, shell]);

  const preview = useMemo(() => {
    if (!me || !s.mask) return null;
    return walk(s.mask, me.x, me.footY, moveDx, FUEL, s.cloudLevel);
  }, [me, s.mask, moveDx, s.cloudLevel]);

  const step = (dir: -1 | 1) => {
    if (!me || !s.mask) return false;
    const target = Math.max(-MAX_MOVE, Math.min(MAX_MOVE, moveDx + dir));
    if (target === moveDx) return false;
    const p = walk(s.mask, me.x, me.footY, target, FUEL, s.cloudLevel);
    if (p.dx !== target) return false;
    setMoveDx(target);
    return true;
  };

  // 내 차례 알림. 처음 차례에는 전장 가운데를 향하게 각도를 잡아 준다(오른쪽 절반이면 왼쪽으로).
  const wasMyTurn = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !angleTouched.current && me) setAngleRaw(me.x > 320 ? 135 : 45);
    if (myTurn && !wasMyTurn.current) {
      playChime();
      if (!muted) navigator.vibrate?.(120);
    }
    wasMyTurn.current = myTurn;
  }, [myTurn, playChime, muted, me]);

  // 대본이 오면 쏜 사람의 각도를 기억(포신 방향)하고 낭독 문장을 만든다.
  const [announcement, setAnnouncement] = useState('');
  const replayNonce = s.replay?.nonce;
  useEffect(() => {
    const r = useArtilleryStore.getState().replay?.report;
    if (!r) return;
    setLastAngles((prev) => ({ ...prev, [r.seat]: r.angle }));
    if (r.seat === mySeat) setLastPower(r.power);
    setAnnouncement(shotSummary(r, nameOf));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [replayNonce]);
  useEffect(() => {
    if (!s.notice) return;
    setAnnouncement(`${turnSeatName.current} — ${s.notice.text === 'TIMEOUT' ? '시간 초과로 넘김' : '넘김'}`);
  }, [s.notice]);
  const turnSeatName = useRef('');
  turnSeatName.current = s.turnSeat >= 0 ? nameOf(s.turnSeat) : '';

  const fire = (p: number) => {
    if (!canAct || !preview) return;
    s.setPending(true);
    sendAction({ '@action': 'FIRE', moveDx: preview.dx, angle, power: p, shell });
  };
  const skip = () => {
    if (!canAct) return;
    s.setPending(true);
    sendAction({ '@action': 'SKIP' });
  };

  const exit = () => {
    if (!onExit) return;
    if (
      !spectator &&
      s.result === null &&
      !roomFinished &&
      me?.alive &&
      !window.confirm('게임 중에 나가면 탈주로 처리되어 최하위가 됩니다. 나가시겠습니까?')
    ) {
      return;
    }
    onExit();
  };

  const windText = s.wind === 0 ? '바람 없음' : `바람 ${s.wind < 0 ? '←' : '→'} ${Math.abs(s.wind)}`;
  const suddenDeath = s.turnNo >= s.suddenDeathTurn && s.suddenDeathTurn > 0;

  return (
    <div className="ar-table">
      <header className="ar-header">
        <div className="ar-header-badges">
          <span className="ar-badge ar-badge-title">씨앗포</span>
          <span className="ar-badge">{playerIds.length}인</span>
          {practice && <span className="ar-badge">🤖 연습</span>}
          {spectatorCount > 0 && <span className="ar-badge">관전 {spectatorCount}</span>}
          {spectator && <span className="ar-badge">관전 모드</span>}
          <span className="ar-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="ar-header-badges">
          <button
            type="button"
            className="ar-badge"
            onClick={toggleMute}
            aria-label="효과음"
            aria-pressed={!muted}
            title={muted ? '효과음 켜기' : '효과음 끄기'}
          >
            {muted ? '🔇' : '🔊'}
          </button>
          <VoiceControls voice={voice} buttonClassName="ar-badge" />
          {!spectator && (
            <span className="ar-reaction-bar">
              <button
                type="button"
                className="ar-badge"
                onClick={() => setReactionOpen((v) => !v)}
                aria-label="이모지 반응"
                aria-expanded={reactionOpen}
              >
                😀
              </button>
              {reactionOpen && (
                <span className="ar-reaction-palette">
                  {REACTIONS.map((e) => (
                    <button
                      type="button"
                      key={e}
                      onClick={() => {
                        sendReaction(e);
                        setReactionOpen(false);
                      }}
                    >
                      {e}
                    </button>
                  ))}
                </span>
              )}
            </span>
          )}
          <button type="button" className="ar-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button type="button" className="ar-badge" onClick={rules.show} title="게임 방법 — 차례 시간은 계속 흐릅니다">
            규칙
          </button>
          {onAbort && !roomFinished && (
            <button type="button" className="ar-badge" onClick={onAbort}>
              게임 종료
            </button>
          )}
          {onExit && (
            <button type="button" className="ar-badge" onClick={exit}>
              나가기
            </button>
          )}
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <p className="ar-error" role="alert">
          <span>{s.errorMessage}</span>
          <button type="button" className="ar-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
            ×
          </button>
        </p>
      )}

      <ol className="ar-seats" aria-label="좌석">
        {s.units.map((u) => {
          const uid = playerIds[u.seat];
          const isTurn = u.seat === turnSeat;
          const disconnected = s.disconnectedSeats.has(u.seat);
          const v = voiceStatusOf(voice, uid);
          return (
            <li
              key={u.seat}
              className={`ar-seat${isTurn ? ' ar-seat-turn' : ''}${u.alive ? '' : ' ar-seat-out'}`}
              style={{ ['--ar-accent' as string]: seatColor(u.seat) }}
            >
              <span className="ar-seat-name">
                {tiers[uid] && <TierBadge tier={tiers[uid]} size={16} />}
                {nameOf(u.seat)}
                {u.seat === mySeat ? ' (나)' : ''}
              </span>
              <span className="ar-seat-hp" aria-label={`체력 ${u.hp}`}>
                <span className="ar-seat-hp-bar" style={{ width: `${u.alive ? u.hp : 0}%` }} />
              </span>
              <span className="ar-seat-ammo" title="남은 호박탄">
                🎃{u.pumpkins}
              </span>
              {isTurn && <span className="ar-seat-tag">차례</span>}
              {!u.alive && <span className="ar-seat-tag">{OUT_LABEL[u.outCause ?? ''] ?? '탈락'}</span>}
              {disconnected && u.alive && <span className="ar-seat-tag">끊김</span>}
              {v === 'on' && <span aria-label="음성 참여 중">🎧</span>}
              {onKick && disconnected && !roomFinished && u.seat !== mySeat && u.alive && (
                <button type="button" className="ar-seat-kick" onClick={() => onKick(uid)}>
                  내보내기
                </button>
              )}
              {chatLines[u.seat] && (
                <span key={chatLines[u.seat].id} className="ar-seat-chat" title={chatLines[u.seat].text}>
                  {chatLines[u.seat].text}
                </span>
              )}
              {liveReactions[u.seat] && (
                <span key={liveReactions[u.seat].id} className="ar-seat-reaction" aria-hidden="true">
                  {liveReactions[u.seat].emoji}
                </span>
              )}
            </li>
          );
        })}
      </ol>

      <div className={`ar-stage${myTurn ? ' ar-stage-myturn' : ''}`}>
        <div className="ar-status">
          <span className="ar-badge ar-wind" aria-label={windText}>
            🍃 {windText}
          </span>
          {suddenDeath && <span className="ar-badge ar-badge-warn">☁ 구름이 차오른다</span>}
          {!roomFinished && s.phase === 'AIMING' && turnSeat >= 0 && <ArtilleryCountdown turnSeconds={turnSeconds} />}
          {myTurn ? (
            <span className="ar-badge ar-badge-turn">내 차례</span>
          ) : (
            !roomFinished &&
            turnSeat >= 0 && <span className="ar-badge">{nameOf(turnSeat)} 조준 중</span>
          )}
        </div>
        <ArtilleryField
          mask={s.mask}
          units={s.units}
          replay={s.replay}
          cloudLevel={s.cloudLevel}
          turnSeat={s.replay ? -1 : turnSeat}
          mySeat={mySeat}
          names={names}
          aim={myTurn && me && preview ? { seat: mySeat, angle, power, x: preview.x, footY: preview.footY } : null}
          angles={lastAngles}
          onAimAngle={canAct ? setAngle : undefined}
          onReplayDone={s.finishReplay}
          reducedMotion={reducedMotion}
        />
      </div>

      {myTurn && (
        <ArtilleryControls
          angle={angle}
          power={power}
          lastPower={lastPower}
          shell={shell}
          pumpkins={me?.pumpkins ?? 0}
          fuelLeft={preview?.fuelLeft ?? FUEL}
          moveDx={moveDx}
          enabled={canAct}
          onAngle={setAngle}
          onPower={setPower}
          onShell={setShell}
          onStep={step}
          onResetMove={() => setMoveDx(0)}
          onFire={fire}
          onSkip={skip}
        />
      )}

      {s.result && !s.replay && (
        <ArtilleryMatchEnd result={s.result} mySeat={mySeat} nameOf={nameOf} onExit={onExit} onBackToRoom={onBackToRoom} />
      )}

      {roomFinished && !s.result && (
        <section className="ar-end" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="ar-btn ar-btn-fire" onClick={exit}>
              메인으로
            </button>
          )}
        </section>
      )}

      <div className="ar-sr-only" aria-live="polite" aria-atomic="true">
        {announcement}
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

      <TutorialDialog tutorial={ARTILLERY_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
