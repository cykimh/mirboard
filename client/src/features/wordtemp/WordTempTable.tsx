import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import type { Tier } from '@/api/users';
import { GameIcon } from '@/components/GameIcon';
import { GameMenu } from '@/components/GameMenu';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { ErrorNotice } from '@/components/notice/FloatingNotice';
import { useAuthStore } from '@/features/auth/authStore';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { playTableSfx } from '@/features/sound/tableSfx';
import { useSfx } from '@/features/sound/useSfx';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useStompRoom } from '@/ws/useStompRoom';
import { HintBoard } from './HintBoard';
import { SeatStrip } from './SeatStrip';
import { WordTempMatchEnd } from './WordTempMatchEnd';
import { AttemptResultPanel, GuessPanel, SetterPanel, TurnEndPanel, WordPicker } from './WordTempPanels';
import { ATTEMPT_FLOORS, clockText } from './wordTempMath';
import { wordTempRoomSink } from './wordTempRoomSink';
import { SEND_GUARD_MS, iAmPlaying, iAmSetter, scoringInProgress, useWordTempStore, type PhaseClock } from './wordTempStore';
import { WORD_TEMP_TOKENS_CLASS } from './wordTempTheme';
import { WORD_TEMP_TUTORIAL } from './tutorial/wordTempTutorial';

/** `BUSY` 뒤 다시 보내기까지(ms) — 방 락이 풀릴 틈. */
export const BUSY_RETRY_DELAY_MS = 150;
/**
 * 첫 판을 아직 못 받았을 때 다시 청하는 간격(ms)과 횟수. 이 게임은 판 시작 때 이벤트를 보내지 않는다(첫 낱말 고르기는 resync 로 받는다 —
 * 룰 §12.3) — 방이 막 시작돼 서버가 상태를 쓰기 전에 첫 resync 가 닿으면 다음 공개 이벤트(20초 뒤)까지 판이 비어 낱말을 고를 수 없다.
 */
export const LOAD_RETRY_MS = 700;
export const LOAD_RETRY_MAX = 20;
/** 남은 시간이 이 아래면 시계가 재촉한다(초). */
const CLOCK_WARN_SEC = 10;

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

/** 지금 단계의 남은 시간 — 서버가 준 남은 ms(공개 뷰 `phaseRemainingMs`·단계 시작 이벤트)를 받은 때부터 줄인다. */
function useRemaining(clock: PhaseClock | null): number | null {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!clock) return;
    setNow(Date.now());
    const t = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(t);
  }, [clock]);
  if (!clock) return null;
  return Math.max(0, clock.remainingMs - (now - clock.since));
}

const CLOCK_LABEL: Record<string, string> = {
  WORD_SELECT: '고르기',
  HINT: '힌트',
  GUESS: '추리',
  ATTEMPT_RESULT: '결과',
  TURN_END: '다음',
};

/**
 * 낱말 온도 게임판 조립 루트(D-319, `docs/rules-wordtemp.md`). 다른 게임판처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>화면은 위에서 아래로: 머리 줄(라운드·단계 시계, 붙박이) → 좌석 띠(누가 출제자인지·누가 냈는지) → <b>온도계 판</b>(모두에게 공개된 힌트와
 * 온도) → 내 칸(낱말 고르기 / 출제자: 정답·내 힌트와 온도·입력 / 추리자: 답 입력) → 결과. 입력란이 공개 힌트 바로 아래라 휴대폰 자판이
 * 올라와도 힌트와 시계가 같이 보인다.
 *
 * <p><b>비밀</b> — 출제자의 힌트·온도, 남의 후보, 공개 전 답은 서버가 보내지 않는다. 화면도 한 번 더 지킨다: 출제자 칸은 내가 출제자일 때만
 * 그리고, 관전자·빠진 좌석에게는 입력 칸이 없다.
 */
export function WordTempTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  usernames = {},
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
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  // 스토어가 내 좌석을 알아야 본인 큐를 내 것으로 읽는다 — 첫 스냅샷 전에(레이아웃 단계에서) 알린다
  useLayoutEffect(() => {
    useWordTempStore.getState().setMySeat(mySeat);
  }, [mySeat]);
  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
    roomId,
    token,
    wordTempRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const { muted, toggleMute } = useSfx();
  const chatLines = useSeatChatLines(playerIds);
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(WORD_TEMP_TUTORIAL.seenKey, false);
  // 연출(수은주·방울·축하)은 앱의 연출 설정을 따른다 — 꺼져 있으면 `wt-anim` 이 없어 CSS 애니메이션이 돌지 않는다. 소리는 음소거만 따른다.
  const animate = useCardAnimStore((st) => st.enabled);

  const s = useWordTempStore();
  const remaining = useRemaining(roomFinished || s.result ? null : s.clock);

  // 첫 판이 올 때까지 다시 청한다(위 LOAD_RETRY_MS 주석). 받으면 멈춘다.
  const loading = s.phase === null && !roomFinished;
  const [loadTries, setLoadTries] = useState(0);
  useEffect(() => {
    if (!loading || loadTries >= LOAD_RETRY_MAX) return;
    const t = window.setTimeout(() => {
      requestResync();
      setLoadTries((n) => n + 1);
    }, LOAD_RETRY_MS);
    return () => window.clearTimeout(t);
  }, [loading, loadTries, requestResync]);

  const resyncNonce = s.resyncNonce;
  useEffect(() => {
    if (resyncNonce > 0) requestResync();
  }, [resyncNonce, requestResync]);

  // BUSY(방 락 경합) — 보낸 것을 한 번만 다시 보낸다(sink 가 신호를 올린다).
  const retryNonce = s.retryNonce;
  useEffect(() => {
    if (retryNonce === 0) return;
    const t = window.setTimeout(() => {
      const sent = useWordTempStore.getState().sending;
      if (sent) sendAction(sent.action);
    }, BUSY_RETRY_DELAY_MS);
    return () => window.clearTimeout(t);
  }, [retryNonce, sendAction]);

  // 보낸 것에 소식이 없으면(반쯤 끊긴 망) 막음을 풀고 판을 다시 받는다.
  const sendingId = s.sending?.id;
  useEffect(() => {
    if (sendingId === undefined) return;
    const t = window.setTimeout(() => {
      const st = useWordTempStore.getState();
      if (st.sending?.id !== sendingId) return;
      st.clearSending(sendingId);
      requestResync();
    }, SEND_GUARD_MS);
    return () => window.clearTimeout(t);
  }, [sendingId, requestResync]);

  /** 액션 하나를 보낸다. 응답을 기다리는 동안에는 또 보내지 않는다. */
  const send = useCallback(
    (action: Record<string, unknown>): boolean => {
      const st = useWordTempStore.getState();
      if (st.sending) return false;
      if (!sendAction(action)) {
        st.setError('연결이 끊겨 보내지 못했어요. 잠시 뒤 다시 해 주세요.');
        return false;
      }
      st.noteSent(action);
      playTableSfx('temp.send');
      return true;
    },
    [sendAction],
  );

  const nameOf = useCallback(
    (seat: number) => {
      const uid = playerIds[seat];
      if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
      return usernames[uid] ?? `#${uid ?? seat}`;
    },
    [playerIds, botSeats, usernames],
  );

  const exit = () => {
    if (!onExit) return;
    if (s.result === null && !roomFinished && iAmPlaying(s)) {
      if (!window.confirm('판 도중에 나가면 최하위가 됩니다. 나갈까요?')) return;
    }
    onExit();
  };

  const playing = iAmPlaying(s) && !roomFinished;
  const setter = iAmSetter(s);
  const setterName = s.setterSeat >= 0 ? nameOf(s.setterSeat) : '';
  const busy = s.sending !== null;
  const inTurn = s.phase === 'HINT' || s.phase === 'GUESS' || s.phase === 'ATTEMPT_RESULT' || s.phase === 'TURN_END';
  const lastAttempt = s.attempts.length ? s.attempts[s.attempts.length - 1] : null;
  const lastSummary = s.history.length ? s.history[s.history.length - 1] : null;

  // 차례 끝 다음에 무슨 일이 있나 — 출제 순서에서 살아 있는 다음 좌석, 없으면 다음 라운드·판 끝.
  let next = '';
  if (s.phase === 'TURN_END') {
    const at = s.order.indexOf(s.setterSeat);
    const later = at >= 0 ? s.order.slice(at + 1).find((seat) => s.seats[seat]?.alive) : undefined;
    if (later !== undefined) next = `다음 출제자는 ${nameOf(later)} 님이에요.`;
    else if (s.round < s.rounds) next = `곧 ${s.round + 1}라운드 — 새 낱말을 골라요.`;
    else next = '곧 결과가 나와요.';
  }

  let title = '';
  if (s.phase === 'WORD_SELECT') title = `${s.round}라운드 · 낱말 고르기`;
  else if (s.phase === 'HINT') title = `${setter ? '내' : `${setterName} 님의`} 문제 · ${s.attempt}차 힌트`;
  else if (s.phase === 'GUESS') title = `${setter ? '내' : `${setterName} 님의`} 문제 · ${s.attempt}차 추리`;
  else if (s.phase === 'ATTEMPT_RESULT') title = `${setter ? '내' : `${setterName} 님의`} 문제 · ${s.attempt}차 결과`;
  else if (s.phase === 'TURN_END') title = `${setter ? '내' : `${setterName} 님의`} 문제 · 차례 끝`;

  let status: string;
  if (roomFinished && !s.result) status = '게임이 종료되었습니다.';
  else if (s.result) status = '판이 끝났어요.';
  else if (s.phase === null) status = '판을 불러오는 중…';
  else if (spectator || mySeat < 0) status = '관전 중 — 공개된 것만 보여요';
  else if (!playing) status = '이 판에서 빠졌어요 — 구경만 할 수 있어요';
  else if (s.phase === 'WORD_SELECT') status = s.chosen >= 0 ? '다른 사람이 고르기를 기다려요' : '이번 라운드에 낼 낱말을 골라 주세요';
  else if (s.phase === 'HINT')
    status = setter
      ? scoringInProgress(s)
        ? '힌트를 채점하는 중…'
        : '힌트를 내 주세요 — 온도는 나만 보여요'
      : `${setterName} 님이 힌트를 고르고 있어요 — 낱말과 온도는 공개 때까지 비밀!`;
  else if (s.phase === 'GUESS')
    status = setter
      ? `추리를 기다려요 (${s.guessedSeats.length}/${s.guessSeats.length})`
      : s.myGuess !== null
        ? '모두가 내면 한꺼번에 공개돼요'
        : '공개된 힌트와 온도를 보고 정답을 맞혀 보세요';
  else if (s.phase === 'ATTEMPT_RESULT') status = '답이 공개됐어요';
  else status = '정답과 힌트가 모두 공개됐어요';

  const showClock = remaining !== null && s.phase !== null && s.phase !== 'FINISHED';
  const inlineClock = showClock ? clockText(remaining!) : null;
  const warn = showClock && (s.phase === 'HINT' || s.phase === 'GUESS') && remaining! <= CLOCK_WARN_SEC * 1000;

  return (
    <div
      className={`wt-table ${WORD_TEMP_TOKENS_CLASS}${animate ? ' wt-anim' : ''}`}
      data-phase={s.phase ?? 'LOADING'}
      data-role={spectator || mySeat < 0 ? 'spectator' : setter ? 'setter' : 'guesser'}
    >
      <header className="wt-header gm-header">
        <div className="wt-header-info gm-header-info">
          <GameIcon gameId="WORD_TEMP" size={22} />
          <span className="wt-badge wt-badge-title">낱말 온도</span>
          {s.phase !== null && (
            <span className="wt-badge" aria-label={`${s.round}라운드, 모두 ${s.rounds}라운드`}>
              {s.round}/{s.rounds}R
            </span>
          )}
          {spectator && <span className="wt-badge">👀 관전 중</span>}
        </div>
        {showClock && (
          <div
            className={`wt-clock${warn ? ' wt-clock-warn' : ''}`}
            role="timer"
            aria-live="off"
            aria-label={`${CLOCK_LABEL[s.phase!] ?? '남은 시간'} 남은 시간`}
          >
            <span className="wt-clock-label">{CLOCK_LABEL[s.phase!] ?? ''}</span>
            <span className="wt-clock-value">{clockText(remaining!)}</span>
          </div>
        )}
        <div className="wt-header-actions gm-header-actions">
          <button type="button" className="wt-badge" onClick={() => setChatOpen((v) => !v)} aria-pressed={chatOpen}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <GameMenu
            triggerClassName="wt-badge"
            info={[practice && '봇과 연습', spectatorCount > 0 && `관전 ${spectatorCount}명`]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onAbort={onAbort}
            onExit={onExit ? exit : undefined}
          />
        </div>
      </header>

      <SeatStrip
        seats={s.seats}
        phase={s.result ? 'FINISHED' : s.phase}
        setterSeat={s.result ? -1 : s.setterSeat}
        mySeat={mySeat}
        hintCount={s.hintCount}
        maxHints={s.maxHints}
        guessSeats={s.guessSeats}
        guessedSeats={s.guessedSeats}
        deltas={s.deltas}
        playerIds={playerIds}
        nameOf={nameOf}
        tiers={tiers}
        disconnected={s.disconnectedSeats}
        chatLines={chatLines}
        onKick={onKick && !roomFinished ? onKick : undefined}
      />

      <div className="wt-stage">
        {/* D-269 — 끊김·거절 안내는 화면 위에 뜬다(흐름에 끼우지 않는다). */}
        <ReconnectBanner connected={connected} />
        {/* 첫 판을 다시 청하는 동안의 "아직 없음"은 띄우지 않는다 — 끝내 못 받았을 때만 알린다 */}
        <ErrorNotice
          message={loading && loadTries < LOAD_RETRY_MAX ? null : s.errorMessage}
          seq={s.errorSeq}
          sticky={s.errorSticky}
          onClose={() => s.setError(null)}
        />

        {!s.result && title && (
          <div className="wt-phase">
            <h2 className="wt-phase-title">{title}</h2>
            {inTurn && (
              <ol className="wt-attempts" aria-label="차수">
                {ATTEMPT_FLOORS.map((floor, i) => {
                  const a = i + 1;
                  const state = a < s.attempt ? 'done' : a === s.attempt ? 'now' : 'todo';
                  return (
                    <li
                      key={a}
                      className={`wt-attempt wt-attempt-${state}`}
                      aria-current={state === 'now' ? 'step' : undefined}
                    >
                      <span className="wt-attempt-no">{a}차</span>
                      <span className="wt-attempt-floor">최저 {floor}°</span>
                    </li>
                  );
                })}
              </ol>
            )}
          </div>
        )}

        {!s.result && inTurn && (
          <HintBoard
            phase={s.phase}
            attempt={s.attempt}
            revealed={s.revealed}
            hintCount={s.hintCount}
            maxHints={s.maxHints}
            setterName={setterName}
            mine={setter}
            freshAttempt={s.freshReveal}
            fxSeq={s.fxSeq}
          />
        )}

        {!s.result && s.phase === 'WORD_SELECT' && playing && s.candidates.length > 0 && (
          <WordPicker
            round={s.round}
            candidates={s.candidates}
            chosen={s.chosen}
            busy={busy}
            onPick={(index) => send({ '@action': 'SELECT_WORD', round: s.round, index })}
          />
        )}
        {!s.result && s.phase === 'WORD_SELECT' && !playing && (
          <p className="wt-wait">모두가 이번 라운드에 낼 낱말을 고르고 있어요.</p>
        )}

        {/* 차수 결과는 판 바로 아래 — 출제자에게도 내 힌트보다 먼저 보인다 */}
        {!s.result && s.phase === 'ATTEMPT_RESULT' && lastAttempt && (
          <AttemptResultPanel
            attempt={lastAttempt}
            round={s.round}
            setterSeat={s.setterSeat}
            mySeat={mySeat}
            revealed={s.revealed.find((r) => r.attempt === lastAttempt.attempt)}
            nameOf={nameOf}
            fresh={s.freshResult === lastAttempt.attempt}
          />
        )}

        {/* 출제자 칸 — 내가 출제자일 때만(남의 힌트·온도는 스토어에도 없다) */}
        {!s.result && playing && setter && (s.phase === 'HINT' || s.phase === 'GUESS' || s.phase === 'ATTEMPT_RESULT') && (
          <SetterPanel
            answer={s.answer}
            attempt={s.attempt}
            hints={s.hints}
            hintCount={s.hintCount}
            maxHints={s.maxHints}
            hinting={s.phase === 'HINT'}
            busy={busy}
            freshScored={s.freshScored}
            fxSeq={s.fxSeq}
            clock={inlineClock}
            onHint={(text) => send({ '@action': 'SUBMIT_HINT', turnNo: s.turnNo, attempt: s.attempt, text })}
            onEnd={() => send({ '@action': 'END_HINTS', turnNo: s.turnNo, attempt: s.attempt })}
          />
        )}

        {!s.result && playing && !setter && s.phase === 'GUESS' && s.guessSeats.includes(mySeat) && (
          <GuessPanel
            attempt={s.attempt}
            myGuess={s.myGuess}
            busy={busy}
            clock={inlineClock}
            onGuess={(text) => send({ '@action': 'SUBMIT_GUESS', turnNo: s.turnNo, attempt: s.attempt, text })}
          />
        )}

        {!s.result && s.phase === 'TURN_END' && s.turnReveal && (
          <TurnEndPanel
            reveal={s.turnReveal}
            reason={lastSummary && lastSummary.turnNo === s.turnNo ? lastSummary.reason : null}
            setterName={setterName}
            next={next}
          />
        )}
      </div>

      <p className="wt-status" aria-live="polite">
        {status}
      </p>

      {s.result && (
        <WordTempMatchEnd
          result={s.result}
          history={s.history}
          mySeat={mySeat}
          nameOf={nameOf}
          onExit={onExit}
          onBackToRoom={onBackToRoom}
        />
      )}

      {roomFinished && !s.result && (
        <section className="wt-end" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="wt-primary" onClick={onExit}>
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

      <TutorialDialog tutorial={WORD_TEMP_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
