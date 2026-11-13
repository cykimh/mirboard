import { GameIcon } from '@/components/GameIcon';
import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { ErrorNotice } from '@/components/notice/FloatingNotice';
import { GameMenu } from '@/components/GameMenu';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useLiveReactions } from '@/features/chat/reactionStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { useSfx } from '@/features/sound/useSfx';
import { useVoiceChat, voiceStatusOf } from '@/features/voice/useVoiceChat';
import {
  FUEL,
  ITEM_NAMES,
  SHELL_NAMES,
  UNIT_KINDS,
  type Item,
  type Shell,
  type ShotReport,
  type UnitKind,
} from '@/types/artillery';
import { artilleryRoomSink } from './artilleryRoomSink';
import { useArtilleryStore } from './artilleryStore';
import { ArtilleryField } from './ArtilleryField';
import { ArtilleryControls } from './ArtilleryControls';
import { ArtilleryLoadout } from './ArtilleryLoadout';
import {
  PORTRAIT_HEADROOM,
  canLockLandscape,
  dismissRotateHint,
  lockLandscape,
  rotateHintDismissed,
  useArtilleryLayout,
} from './useArtilleryLayout';
import { ArtilleryCountdown } from './ArtilleryCountdown';
import { ArtilleryMatchEnd } from './ArtilleryMatchEnd';
import { ArtilleryPicker, KIND_ICONS } from './ArtilleryPicker';
import { seatColor } from './draw';
import { minDelay, teamName, turnDelay } from './delay';
import { useLiveMove } from './useLiveMove';
import { useAimPreviewSender } from './useAimPreviewSender';
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

/** 대본 → 한 줄 낭독("보라의 호박탄 — 민수 28 피해, 지수 추락"). 아이템·회복·둔덕도 붙인다(D-224). */
export function shotSummary(r: ShotReport, nameOf: (seat: number) => string): string {
  const parts: string[] = [];
  const dealt = new Map<number, { amount: number; direct: boolean }>();
  for (const st of r.stages) {
    for (const d of st.damages) {
      const prev = dealt.get(d.seat) ?? { amount: 0, direct: false };
      dealt.set(d.seat, { amount: prev.amount + d.amount, direct: prev.direct || d.direct });
    }
  }
  for (const [seat, d] of dealt) parts.push(`${nameOf(seat)} ${d.amount} 피해${d.direct ? '(직격)' : ''}`);
  for (const st of r.stages) for (const f of st.falls) if (!f.intoCloud && f.damage > 0) parts.push(`${nameOf(f.seat)} 낙하 ${f.damage}`);
  for (const e of r.eliminated) parts.push(`${nameOf(e.seat)} ${OUT_LABEL[e.cause] ?? '탈락'}`);
  const shots = r.stages.flatMap((st) => st.shots);
  if (parts.length === 0 && shots.some((sh) => sh.crater?.fill)) parts.push('둔덕이 솟았다');
  const fired = r.stages.length > 0;
  const what = fired ? SHELL_NAMES[r.shell] : '발을 헛디뎠다';
  const last = shots[shots.length - 1];
  const result =
    parts.length > 0 ? parts.join(', ') : last?.end === 'OUT' ? '전장 밖으로' : last?.end === 'CLOUD' ? '구름 속으로' : '빗나감';
  const extra = [r.item ? ITEM_NAMES[r.item] : null, r.heal && r.heal.amount > 0 ? `체력 +${r.heal.amount}` : null]
    .filter(Boolean)
    .join(' ');
  return `${nameOf(r.seat)}의 ${what}${extra ? ` (${extra})` : ''} — ${result}`;
}

/**
 * 씨앗포 게임판 조립 루트 (S3, 설계 `docs/plans/artillery.md` §8). 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>화면 중심은 Canvas 전장이다. 서버 대본을 재생하는 동안은 아무의 차례도 아니고, 내 차례에는 전장을 끌어 각도를, 조작부로 힘·탄·이동을
 * 정한다. S6b(D-251): 걸으면 250ms 마다 `MOVE` 로 그 자리에서 확정하고(한 번에 하나 — `useLiveMove`), 포신 각도·탄은 초당 4번 미리보기로
 * 흘린다(`useAimPreviewSender`, 힘은 숨김). 남의 걸음(`UNIT_MOVED`)·포신(`AIM_PREVIEW`)은 전장이 따라 그린다.
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
  const { connected, sendAction, sendPreview, sendChat, sendReaction, sendVoice, subscribeVoice, chatPanelOpenRef } = useStompRoom(
    roomId,
    token,
    artilleryRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const { muted, toggleMute, playChime } = useSfx();
  const liveReactions = useLiveReactions();
  const chatLines = useSeatChatLines(playerIds);
  const voice = useVoiceChat({ myUserId, connected, send: sendVoice, subscribe: subscribeVoice });
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(ARTILLERY_TUTORIAL.seenKey, false);
  const animate = useCardAnimStore((st) => st.enabled);
  const reducedMotion =
    !animate || (typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true);

  const layout = useArtilleryLayout();
  // D-253 — 세로 화면에서 한 번 "가로로 돌리면 크게"(닫으면 로컬 기억). 터치 기기만.
  const [hintClosed, setHintClosed] = useState(rotateHintDismissed);
  const coarse = typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches === true;
  const rotateHint = layout === 'portrait' && coarse && !hintClosed;
  const closeRotateHint = () => {
    dismissRotateHint();
    setHintClosed(true);
  };

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

  // 조준 상태(이 화면에만) — 차례마다 아이템은 비우고, 각도·힘은 지난 값을 둔다.
  const [angle, setAngleRaw] = useState(45);
  const angleTouched = useRef(false);
  const setAngle = (a: number) => {
    angleTouched.current = true;
    setAngleRaw(a);
  };
  const [power, setPower] = useState(55);
  const [shell, setShell] = useState<Shell>('PEA');
  const [item, setItem] = useState<Item | null>(null);
  const [lastPower, setLastPower] = useState<number | null>(null);
  const [lastAngles, setLastAngles] = useState<Record<number, number>>({});
  const turnKey = `${s.turnSeat}:${s.turnNo}`;
  useEffect(() => {
    setItem(null);
  }, [turnKey]);
  const shells = useMemo(() => {
    if (!me) return [{ shell: 'PEA' as Shell, count: -1 }];
    const list: { shell: Shell; count: number }[] = [
      { shell: 'PEA', count: -1 },
      { shell: 'PUMPKIN', count: me.pumpkins },
    ];
    if (me.special) list.push({ shell: me.special, count: me.specials });
    return list;
  }, [me]);
  useEffect(() => {
    const left = shells.find((x) => x.shell === shell)?.count ?? 0;
    if (left === 0) setShell('PEA');
  }, [shells, shell]);
  useEffect(() => {
    if (me && item && !me.items.includes(item)) setItem(null);
  }, [me, item]);
  const myFuel = me?.fuel ?? FUEL;

  // D-251 — 걷기는 그 자리에서 확정(MOVE 한 번에 하나), 조준은 미리보기(힘 없음). 재생 중·끊김이면 멈춘다.
  const live = useLiveMove({ active: myTurn && connected && s.replay === null, mySeat, turnKey, sendAction });
  const preview = live.predicted;
  const step = live.step;
  useAimPreviewSender({ active: myTurn && connected && s.replay === null, angle, shell, sendPreview });

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
    const t = s.notice.text;
    const who = t === 'FELL' || t === 'WILTED' ? movedSeatName.current : turnSeatName.current;
    const what = t === 'TIMEOUT' ? '시간 초과로 넘김' : t === 'FELL' ? '걷다가 구름 속으로 추락' : t === 'WILTED' ? '걷다가 떨어져 시듦' : '넘김';
    setAnnouncement(`${who} — ${what}`);
  }, [s.notice]);
  const turnSeatName = useRef('');
  turnSeatName.current = s.turnSeat >= 0 ? nameOf(s.turnSeat) : '';
  const movedSeatName = useRef('');
  movedSeatName.current = s.lastMove ? nameOf(s.lastMove.seat) : '';

  // 응답 안 온 MOVE 가 있으면 그 뒤에 — 남은 걸음은 FIRE.moveDx 로(D-251).
  const fire = (p: number) => {
    if (!canAct || !preview) return;
    live.requestFire({ angle, power: p, shell, item });
  };
  const picking = s.phase === 'PICKING' && s.result === null && !roomFinished;
  const canPick = picking && !spectator && mySeat >= 0 && connected && !!me?.alive && !me.picked && s.myPick === null;
  const pick = (kind: UnitKind) => {
    if (!canPick) return;
    s.setMyPick(kind);
    sendAction({ '@action': 'PICK', unit: kind });
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

  // D-226 — 딜레이: 좌석은 가장 작은 딜레이와의 차이, 조작부는 이번 발에 쌓일 값.
  const baseDelay = minDelay(s.units);
  const delayCost = myTurn && preview ? turnDelay(s.delayTable, shell, item, myFuel - preview.fuelLeft) : null;
  // 남의 조준 미리보기(D-229) — 지금 차례 주인 것만 스토어에 남는다.
  const liveAim = s.aimPreview && s.aimPreview.seat !== mySeat ? s.aimPreview : null;
  // 순서·좌석 ⏱ 는 차례가 열린 동안만 — 연출 대기 중에는 쏜 사람의 딜레이가 다음 TURN_CHANGED 에야 오기 때문이다.
  const aiming = s.phase === 'AIMING' && turnSeat >= 0;
  const upcoming = aiming
    ? s.queue.filter((seat) => seat !== turnSeat && s.units[seat]?.alive).slice(0, 3)
    : [];

  // D-252 — 결과가 나오면 이긴 포대가 뛰고 축포가 터진다(스킨 연출).
  const result = s.result;
  const winners = useMemo(() => {
    if (!result) return null;
    const list = result.teams
      ? result.winnerTeam === null || result.winnerTeam === undefined
        ? []
        : result.placements.filter((p) => p.seat % 2 === result.winnerTeam && !p.deserted).map((p) => p.seat)
      : result.placements.filter((p) => p.rank === 1 && !p.deserted).map((p) => p.seat);
    return list.length > 0 ? list : null;
  }, [result]);

  const windText = s.wind === 0 ? '바람 없음' : `바람 ${s.wind < 0 ? '←' : '→'} ${Math.abs(s.wind)}`;
  const suddenDeath = s.turnNo >= s.suddenDeathTurn && s.suddenDeathTurn > 0;

  return (
    <div className={`ar-table ar-layout-${layout}`}>
      <header className="ar-header gm-header">
        <div className="ar-header-badges gm-header-info">
          <GameIcon gameId="ARTILLERY" size={22} />
          <span className="ar-badge ar-badge-title">씨앗포</span>
          <span className="ar-badge">{s.teams ? '2:2 팀전' : `${playerIds.length}인`}</span>
          {spectator && <span className="ar-badge">관전 모드</span>}
        </div>
        <div className="ar-header-badges gm-header-actions">
          <button type="button" className="ar-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          {/* D-186 — 방 정보·설정·규칙·반응·게임 종료·나가기는 ⋮ 메뉴로(머리 줄 한 줄 고정). */}
          <GameMenu
            triggerClassName="ar-badge"
            info={[
              practice && '봇과 연습',
              spectatorCount > 0 && `관전 ${spectatorCount}명`,
              connected ? '연결됨' : '연결 끊김',
            ]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onReaction={spectator ? undefined : sendReaction}
            voice={voice}
            onAbort={onAbort && !roomFinished ? onAbort : undefined}
            onExit={onExit ? exit : undefined}
          />
        </div>
      </header>

      <ReconnectBanner connected={connected} />

      <ol className="ar-seats" aria-label="좌석">
        {s.units.map((u) => {
          const uid = playerIds[u.seat];
          const isTurn = u.seat === turnSeat;
          const disconnected = s.disconnectedSeats.has(u.seat);
          const v = voiceStatusOf(voice, uid);
          return (
            <li
              key={u.seat}
              className={`ar-seat${isTurn ? ' ar-seat-turn' : ''}${u.alive ? '' : ' ar-seat-out'}${s.teams ? ` ar-seat-team${u.seat % 2}` : ''}`}
              style={{ ['--ar-accent' as string]: seatColor(u.seat) }}
              aria-label={`${nameOf(u.seat)}${u.seat === mySeat ? ' (나)' : ''} — 체력 ${u.hp}/${u.maxHp}${isTurn ? ', 차례' : ''}`}
            >
              <span className="ar-seat-name">
                {tiers[uid] && <TierBadge tier={tiers[uid]} size={14} />}
                {u.kind && (
                  <span className="ar-seat-kind" title={UNIT_KINDS[u.kind].name} aria-label={UNIT_KINDS[u.kind].name}>
                    {KIND_ICONS[u.kind]}
                  </span>
                )}
                {s.teams && <span className={`ar-team-chip ar-team-${u.seat % 2}`}>{teamName(u.seat % 2)}</span>}
                <span className="ar-seat-label">
                  {nameOf(u.seat)}
                  {u.seat === mySeat ? ' (나)' : ''}
                </span>
              </span>
              {!picking && (
                <span className="ar-seat-ammo" title={`남은 호박탄${u.special ? `·${SHELL_NAMES[u.special]}` : ''}·아이템`}>
                  🎃{u.pumpkins}
                  {u.special ? ` ✦${u.specials}` : ''}
                  {u.items.length > 0 ? ` 🎒${u.items.length}` : ''}
                </span>
              )}
              {aiming && u.alive && u.delay !== undefined && s.delayTable && (
                <span className="ar-seat-delay" title={`딜레이 ${u.delay} — 가장 적은 화분이 다음 차례`}>
                  ⏱{u.delay - baseDelay}
                </span>
              )}
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
          follow={s.follow && s.follow.seat !== mySeat ? s.follow : null}
          liveAim={liveAim && liveAim.seat === turnSeat ? { seat: liveAim.seat, angle: liveAim.angle, at: liveAim.at } : null}
          onAimAngle={canAct ? setAngle : undefined}
          onReplayDone={s.finishReplay}
          reducedMotion={reducedMotion}
          wind={s.wind}
          teams={s.teams}
          winners={s.replay ? null : winners}
          headroom={layout === 'portrait' ? PORTRAIT_HEADROOM : 0}
        >
          <div className="ar-hud" aria-label="전장 정보">
            <div className="ar-hud-tl">
              <span className="ar-badge ar-hud-badge ar-wind" aria-label={windText}>
                🍃 {windText}
              </span>
              {suddenDeath && <span className="ar-badge ar-hud-badge ar-badge-warn">☁ 구름이 차오른다</span>}
              {picking && <span className="ar-badge ar-hud-badge">포대 고르는 중</span>}
            </div>
            <div className="ar-hud-tr">
              {myTurn ? (
                <span className="ar-badge ar-hud-badge ar-badge-turn">내 차례</span>
              ) : (
                !roomFinished &&
                turnSeat >= 0 && (
                  <span className="ar-badge ar-hud-badge ar-aiming">
                    {nameOf(turnSeat)} 조준 중
                    {liveAim?.shell && liveAim.seat === turnSeat && (
                      <span className="ar-aim-shell" title="고른 탄(조준 미리보기)">
                        {SHELL_NAMES[liveAim.shell]}
                      </span>
                    )}
                  </span>
                )
              )}
              {!roomFinished && s.phase === 'AIMING' && turnSeat >= 0 && <ArtilleryCountdown turnSeconds={turnSeconds} />}
              {!roomFinished && !s.result && upcoming.length > 0 && (
                <span className="ar-badge ar-hud-badge ar-queue" aria-label={`다음 차례 ${upcoming.map(nameOf).join(', ')}`}>
                  다음 {upcoming.map((seat) => (seat === mySeat ? '나' : nameOf(seat))).join(' › ')}
                </span>
              )}
            </div>
            <ErrorNotice message={s.errorMessage} seq={s.errorSeq} sticky={s.errorSticky} onClose={s.dismissError} />
            {myTurn && (
              <ArtilleryLoadout
                shell={shell}
                shells={shells}
                items={me?.items ?? []}
                item={item}
                enabled={canAct}
                onShell={setShell}
                onItem={setItem}
              />
            )}
          </div>
        </ArtilleryField>
      </div>

      {rotateHint && (
        <p className="ar-rotate-hint" role="note">
          <span aria-hidden="true">📱↻</span>
          <span className="ar-rotate-text">가로로 돌리면 전장을 크게 볼 수 있어요</span>
          {canLockLandscape() && (
            <button
              type="button"
              className="ar-btn ar-btn-small"
              onClick={() => {
                void lockLandscape();
                closeRotateHint();
              }}
            >
              가로 전체화면
            </button>
          )}
          <button type="button" className="ar-error-close" aria-label="안내 닫기" onClick={closeRotateHint}>
            ×
          </button>
        </p>
      )}

      {picking && (
        <ArtilleryPicker
          units={s.units}
          mySeat={mySeat}
          canPick={canPick}
          myPick={s.myPick}
          nameOf={nameOf}
          onPick={pick}
        />
      )}

      {myTurn && (
        <ArtilleryControls
          angle={angle}
          power={power}
          lastPower={lastPower}
          fuelLeft={preview?.fuelLeft ?? myFuel}
          fuelMax={myFuel}
          enabled={canAct}
          initialFacing={me && me.x > 320 ? -1 : 1}
          onAngle={setAngle}
          onPower={setPower}
          onStep={step}
          onFire={fire}
          onSkip={skip}
          delayCost={delayCost}
          skipDelay={s.delayTable?.skip ?? null}
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
