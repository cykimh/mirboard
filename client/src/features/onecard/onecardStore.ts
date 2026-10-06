import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import {
  cardKey,
  type CardPlayedPayload,
  type CardsDrawnPayload,
  type HandPayload,
  type MatchEndedPayload,
  type OneCardCard,
  type OneCardMatchResult,
  type OneCardPhase,
  type OneCardPrivateView,
  type OneCardSeatView,
  type OneCardSuit,
  type OneCardTableView,
  type PileReshuffledPayload,
  type PlayerEliminatedPayload,
  type RaceOpenedPayload,
  type RaceOutcome,
  type RaceResolvedPayload,
  type TurnChangedPayload,
} from '@/types/onecard';

/** 경쟁 창 — 서버 값에 이 클라 시계 기준 마감 시각을 더한다(진행 막대·BUSY 재시도 판단). */
export interface OneCardClientRace {
  raceId: number;
  ownerSeat: number;
  slot: number;
  jitterX: number;
  jitterY: number;
  windowMillis: number;
  closesAt: number;
}

/** 방금 닫힌 경쟁 — 가운데 안내 한 줄. 다음 카드가 놓이면 지운다. */
export interface LastRace {
  raceId: number;
  ownerSeat: number;
  outcome: RaceOutcome;
  bySeat: number;
}

export type PressAction = 'CALL_ONE_CARD' | 'CATCH';

/** 내가 보낸 누름 — 응답(창 해소·거절)을 기다리는 동안만 있다. */
export interface PendingPress {
  raceId: number;
  action: PressAction;
  attempts: number;
  /**
   * 창이 남의 누름·만료·취소로 닫혔다 — 이 누름의 거절(`NO_RACE`·`BUSY`)이 아직 오는 중이다. 서버는 해소를 승자 처리 락
   * 안에서 먼저 방송하고 늦은 누름은 그 뒤에 처리하므로, 진 쪽에는 대개 해소 이벤트가 거절보다 먼저 닿는다. 이 표식이
   * 있는 누름은 다시 보내지 않고, 오는 거절은 오류가 아니라 "늦었어요"로 끝낸다.
   */
  lost?: boolean;
}

/** 첫 누름 + 재시도 2회 (설계서 §4.4 — 락 경합 `BUSY` 는 창이 열린 동안 최대 2회 재시도). */
export const MAX_PRESS_ATTEMPTS = 3;

export interface OneCardRoomState {
  roomId: string | null;

  // ── 공개 상태 (tableView 미러, 결과값) ──
  phase: OneCardPhase | null;
  seats: OneCardSeatView[];
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  direction: number;
  /** 차례 좌석 — 경쟁 창이 열렸거나 끝났으면 −1. */
  turnSeat: number;
  drawPileCount: number;
  race: OneCardClientRace | null;
  lastRace: LastRace | null;
  result: OneCardMatchResult | null;

  // ── 본인 전용 ──
  mySeat: number;
  hand: OneCardCard[];
  /** 지금 손패의 상태 버전 — 이보다 낮은 손패(낡은 resync·늦게 온 이벤트)는 버린다. */
  handVersion: number;

  // ── UI 로컬 ──
  /** 고른 카드의 `cardKey`. 54장이 모두 달라 값으로 고른다 — 손패를 정렬해 보여 줘도 어긋나지 않는다. */
  selectedKey: string | null;
  /** 7 을 낼 때 지정할 무늬. */
  suitChoice: OneCardSuit | null;
  press: PendingPress | null;
  /** BUSY 재시도 신호 — 게임판이 이 값의 변화를 보고 같은 누름을 다시 보낸다. */
  retryNonce: number;
  /** 경쟁에서 진 내 누름(남이 먼저 이겼거나 창이 닫힘) — 오류 대신 "늦었어요"를 잠깐 보여 준다. */
  raceNotice: 'LATE' | null;
  /**
   * S5 — 낡았다고 의심돼 권위 스냅샷을 청한 창 번호(창마다 한 번). 해소 이벤트가 끝내 안 오는 창 — 서버 타이머가
   * 사라졌거나 방송이 실패했다 — 은 클라가 스스로 닫을 길이 없다.
   */
  staleRaceResyncFor: number | null;
  /**
   * S5 — 다시 받기 신호. 게임판이 이 값의 변화를 보고 훅의 `requestResync` 를 부른다 — sink·스토어는 훅을 모른다(D-103).
   */
  resyncNonce: number;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  turnStartedAt: number;
}

export interface OneCardActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<OneCardTableView, OneCardPrivateView>) => void;
  applyPrivateHand: (payload: HandPayload) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  selectCard: (key: string | null) => void;
  setSuitChoice: (suit: OneCardSuit | null) => void;
  startPress: (raceId: number, action: PressAction) => void;
  /**
   * 내 요청이 `BUSY`·`NO_RACE` 로 거절됐다. 경쟁 누름의 거절이면 처리하고 true, 아니면 false(일반 오류로).
   *
   * <p>`NO_RACE` 는 누름(`CALL_ONE_CARD`·`CATCH`)에서만 나오므로 항상 "늦었어요"다 — 해소 이벤트가 먼저 와 누름 표식을
   * 이미 바꿨어도 같다. `BUSY` 는 내기·먹기의 락 경합에서도 오므로 기다리는 누름이 있을 때만 경쟁 누름의 것으로 본다:
   * 창이 열려 있고 횟수가 남았으면 재시도 신호를 올리고, 더 시도할 창이 없으면(횟수 소진·마감·남이 이김) "늦었어요".
   */
  notePressRejected: (code: 'BUSY' | 'NO_RACE') => boolean;
  clearRaceNotice: () => void;
  /** S5 — 이 창이 낡았다고 의심되면 권위 스냅샷을 청한다. 같은 창으로는 한 번만(신호를 올리지 않는다). */
  requestRaceResync: (raceId: number) => void;
}

const INITIAL: OneCardRoomState = {
  roomId: null,
  phase: null,
  seats: [],
  topCard: null,
  declaredSuit: null,
  attackStack: 0,
  direction: 1,
  turnSeat: -1,
  drawPileCount: 0,
  race: null,
  lastRace: null,
  result: null,
  mySeat: -1,
  hand: [],
  handVersion: 0,
  selectedKey: null,
  suitChoice: null,
  press: null,
  retryNonce: 0,
  raceNotice: null,
  staleRaceResyncFor: null,
  resyncNonce: 0,
  disconnectedSeats: new Set(),
  errorMessage: null,
  turnStartedAt: 0,
};

/** 좌석 하나만 갱신한 새 배열. */
function patchSeat(
  seats: OneCardSeatView[],
  seat: number,
  patch: Partial<OneCardSeatView>,
): OneCardSeatView[] {
  return seats.map((s) => (s.seat === seat ? { ...s, ...patch } : s));
}

function toClientRace(
  race: Omit<OneCardClientRace, 'closesAt'>,
  remainingMillis: number,
): OneCardClientRace {
  return {
    raceId: race.raceId,
    ownerSeat: race.ownerSeat,
    slot: race.slot,
    jitterX: race.jitterX,
    jitterY: race.jitterY,
    windowMillis: race.windowMillis,
    closesAt: Date.now() + remainingMillis,
  };
}

/** 새 손패에 고른 카드가 남아 있으면 선택을 유지한다(벌칙 먹기로 손패가 바뀌어도 고른 카드는 그대로). */
function keepSelection(
  hand: OneCardCard[],
  selectedKey: string | null,
): Pick<OneCardRoomState, 'selectedKey' | 'suitChoice'> | Record<string, never> {
  if (selectedKey !== null && hand.some((c) => cardKey(c) === selectedKey)) return {};
  return { selectedKey: null, suitChoice: null };
}

/**
 * 원카드 방 상태 — 순수 리듀서 (D-124: 순번 판정은 `useStompRoom` 이 sink 앞에서 끝낸다).
 *
 * <p>공개 payload 가 증감이 아니라 **결과값**이라(D-128) 같은 이벤트를 두 번 적용해도 같다. 손패는 비공개
 * 이벤트·resync 가 **전체**를 싣고 `handVersion` 이 단조 증가하므로, 가진 것보다 낮은 버전은 버린다 — 순번이 없는
 * 비공개 이벤트(D-129)와 resync 응답(REST)은 서로 다른 길로 와서 도착 순서가 정해져 있지 않다. 뒤바뀌어도 손패가
 * 되돌아가지 않는다. (D-126 이후 서버는 resync 를 방 락 안에서 읽지만, 응답과 STOMP 프레임의 순서는 그래도 보장되지 않는다.)
 */
export const useOneCardStore = create<OneCardRoomState & OneCardActions>((set, get) => ({
  ...INITIAL,

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    const state = get();
    const priv = snap.privateHand;
    const race = t.race ? toClientRace(t.race, t.race.remainingMillis) : null;

    // 관전자는 privateHand 가 null 이다(서버 계약). 낡은 손패(더 낮은 버전)면 지금 손패를 지킨다.
    let mine: Partial<OneCardRoomState>;
    if (priv === null) {
      mine = { mySeat: -1, hand: [], handVersion: 0, selectedKey: null, suitChoice: null };
    } else if (priv.handVersion >= state.handVersion) {
      mine = {
        mySeat: priv.seat,
        hand: priv.hand,
        handVersion: priv.handVersion,
        ...keepSelection(priv.hand, state.selectedKey),
      };
    } else {
      mine = { mySeat: priv.seat };
    }

    set({
      phase: t.phase,
      seats: t.seats,
      topCard: t.topCard,
      declaredSuit: t.declaredSuit,
      attackStack: t.attackStack,
      direction: t.direction,
      turnSeat: t.turnSeat,
      drawPileCount: t.drawPileCount,
      race,
      // 방금 닫힌 경쟁 안내는 서버 뷰에 없는 값이다 — 오래 떠났다 돌아와도 몇 분 전 줄이 남지 않게 비운다.
      lastRace: null,
      result: t.result,
      ...mine,
      // S5 — 기다리던 누름은 같은 창이라도 비운다. 끊긴 사이에 보낸 누름은 버려졌을 수 있다(소켓이 이미 죽어 있었다) —
      // 남겨 두면 그 창 동안 다시 누를 수 없었다. 다시 누른 것이 늦으면 서버가 NO_RACE 로 거절할 뿐이다.
      press: null,
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
      turnStartedAt: Date.now(),
    });
  },

  applyPrivateHand(payload) {
    const state = get();
    if (payload.handVersion < state.handVersion) return;
    set({
      mySeat: payload.seat,
      hand: payload.hand,
      handVersion: payload.handVersion,
      ...keepSelection(payload.hand, state.selectedKey),
    });
  },

  applyEvent(envelope) {
    const { type, payload } = envelope;
    const state = get();

    // D-122 와 같은 심층 방어 — 매치가 끝났으면 진행 이벤트는 반영하지 않는다(종료 화면을 유지하는 동안
    // 잔여 이벤트가 판을 움직이지 않게). 연결 상태 배지는 그대로 반영한다.
    if (state.result && type !== 'PLAYER_DISCONNECTED' && type !== 'PLAYER_RECONNECTED') {
      return 'ignored';
    }

    switch (type) {
      case 'PLAYER_DISCONNECTED':
      case 'PLAYER_RECONNECTED': {
        const { seat } = payload as { seat: number };
        const next = new Set(state.disconnectedSeats);
        if (type === 'PLAYER_DISCONNECTED') next.add(seat);
        else next.delete(seat);
        set({ disconnectedSeats: next });
        return 'applied';
      }

      case 'CARD_PLAYED': {
        const p = payload as CardPlayedPayload;
        set({
          topCard: p.card,
          declaredSuit: p.declaredSuit ?? null,
          attackStack: p.attackStack,
          direction: p.direction,
          seats: patchSeat(state.seats, p.seat, { handCount: p.handCount }),
          // 다음 차례는 TURN_CHANGED(또는 경쟁 창)가 정한다 — 그 사이 '내 차례'를 잘못 세우지 않는다.
          turnSeat: -1,
          lastRace: null,
          // 경쟁 중에는 내기가 막혀 있어(requireTurn) 카드는 항상 창이 닫힌 뒤에 놓인다 — 거절이 끝내 안 온 진 누름이
          // 남아 있다면 여기서 정리한다(안 하면 내 카드 내기의 BUSY 가 "늦었어요"로 잘못 읽힌다).
          press: null,
          ...(p.seat === state.mySeat ? { selectedKey: null, suitChoice: null } : {}),
        });
        return 'applied';
      }

      case 'CARDS_DRAWN': {
        const p = payload as CardsDrawnPayload;
        set({
          seats: patchSeat(state.seats, p.seat, { handCount: p.handCount }),
          drawPileCount: p.drawPileCount,
        });
        return 'applied';
      }

      case 'PILE_RESHUFFLED': {
        const p = payload as PileReshuffledPayload;
        set({ drawPileCount: p.drawPileCount });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as TurnChangedPayload;
        set({
          phase: 'PLAYING',
          turnSeat: p.seat,
          direction: p.direction,
          attackStack: p.attackStack,
          turnStartedAt: Date.now(),
          // 매 플레이 resync 가 지워 주던 거절 문구를 차례가 바뀔 때 지운다(D-126 의 티츄와 같은 처리).
          errorMessage: null,
        });
        return 'applied';
      }

      case 'RACE_OPENED': {
        const p = payload as RaceOpenedPayload;
        set({
          phase: 'RACE',
          race: toClientRace(p, p.windowMillis),
          turnSeat: -1,
          lastRace: null,
          press: null,
          raceNotice: null,
        });
        return 'applied';
      }

      case 'RACE_RESOLVED': {
        const p = payload as RaceResolvedPayload;
        // 지금 창의 해소만 창을 닫는다(다른 창 번호면 다른 창이 열려 있는 것이다).
        const current = state.race?.raceId === p.raceId;
        // 이 창에 내가 보낸 누름이 있었다면 — 내가 이겼으면 끝(안내 없음), 졌으면 "늦었어요". 진 사실은 해소 이벤트에서
        // 바로 알리고(거절이 뒤따르는 순서에 기대지 않는다), 누름은 lost 로 남겨 오는 거절을 오류로 새지 않게 받는다.
        const mine = state.press !== null && state.press.raceId === p.raceId;
        const iWon = p.bySeat >= 0 && p.bySeat === state.mySeat;
        set({
          phase: current || state.race === null ? 'PLAYING' : state.phase,
          race: current ? null : state.race,
          lastRace: {
            raceId: p.raceId,
            ownerSeat: current ? state.race!.ownerSeat : -1,
            outcome: p.outcome,
            bySeat: p.bySeat,
          },
          press: !mine ? state.press : iWon ? null : { ...state.press!, lost: true },
          raceNotice: mine && !iWon ? 'LATE' : state.raceNotice,
        });
        return 'applied';
      }

      case 'PLAYER_ELIMINATED': {
        const p = payload as PlayerEliminatedPayload;
        set({
          // 탈락자 손패는 뽑을 더미 맨 아래로 간다(§10) — 장수는 0, 더미 장수는 최종값.
          seats: patchSeat(state.seats, p.seat, { eliminated: p.reason, handCount: 0 }),
          drawPileCount: p.drawPileCount,
        });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({ phase: 'ENDED', result: p, turnSeat: -1, race: null, press: null });
        return 'applied';
      }

      default:
        // MATCH_STARTED(서버는 보내지 않는다 — 시작 상태는 resync 로 받는다) 등 — 훅이 resync 로 자기치유한다.
        return 'unhandled';
    }
  },

  setError(message) {
    set({ errorMessage: message });
  },

  selectCard(key) {
    set({ selectedKey: key, suitChoice: null });
  },

  setSuitChoice(suit) {
    set({ suitChoice: suit });
  },

  startPress(raceId, action) {
    set({ press: { raceId, action, attempts: 1 }, raceNotice: null });
  },

  notePressRejected(code) {
    const { press, race, retryNonce } = get();
    // 이벤트가 먼저 와서 누름 표식을 바꿨거나 지웠어도(resync 등) 같다.
    if (code === 'NO_RACE') {
      set({ press: null, raceNotice: 'LATE' });
      // S5 — 그 창을 기다리던 누름인데 창이 아직 열려 보인다: 해소 이벤트를 못 받았다(서버는 닫아 저장했는데 방송이
      // 실패했거나, 순서가 뒤바뀌어 오는 중이다). 창마다 한 번 권위 스냅샷을 받는다 — 뒤바뀐 경우면 한 번 더 받을 뿐이다.
      if (race !== null && press?.raceId === race.raceId) get().requestRaceResync(race.raceId);
      return true;
    }
    // BUSY 는 내기·먹기의 락 경합에서도 온다 — 창이 없을 때 기다리는 누름이 없으면 일반 오류로 둔다.
    if (!press) {
      // S5 — 창이 열려 보이는데 기다리는 누름이 없다: resync 스냅샷이 표식을 비운 뒤 그 누름의 BUSY 가 늦게 왔다(창이 열린
      // 동안 내기·먹기는 버튼이 막혀 BUSY 는 누름의 것이다). 버튼은 이미 다시 누를 수 있다 — 빨간 오류 없이 삼킨다.
      return race !== null;
    }
    const open =
      !press.lost && race !== null && race.raceId === press.raceId && Date.now() < race.closesAt;
    if (open && press.attempts < MAX_PRESS_ATTEMPTS) {
      set({ press: { ...press, attempts: press.attempts + 1 }, retryNonce: retryNonce + 1 });
      return true;
    }
    // 더 시도할 창이 없다(횟수 소진·마감·남이 이김) — "잠시 후 다시 시도하세요" 는 틀린 안내라 "늦었어요" 로 끝낸다.
    set({ press: null, raceNotice: 'LATE' });
    return true;
  },

  clearRaceNotice() {
    set({ raceNotice: null });
  },

  requestRaceResync(raceId) {
    const { staleRaceResyncFor, resyncNonce } = get();
    if (staleRaceResyncFor === raceId) return;
    set({ staleRaceResyncFor: raceId, resyncNonce: resyncNonce + 1 });
  },
}));
