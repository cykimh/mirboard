import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  ArtilleryPrivateView,
  ArtilleryTableView,
  MatchEndedPayload,
  MatchResult,
  Phase,
  ShotReport,
  TurnChangedPayload,
  TurnSkippedPayload,
  UnitEliminatedPayload,
  UnitView,
} from '@/types/artillery';
import { carve, maskFromRuns, type Mask } from './terrain';
import type { FrameUnit } from './replay';

/** 재생 중인 발사 — 게임판이 시작 시각부터 `frameAt` 으로 그린다. */
export interface Replay {
  report: ShotReport;
  before: FrameUnit[];
  /** 대본 이전 지형(폭발 순간까지 이걸 그린다). */
  maskBefore: Mask;
  nonce: number;
}

export interface TurnClock {
  since: number;
  remainingMs: number | null;
}

export interface ArtilleryRoomState {
  roomId: string | null;
  phase: Phase;
  units: UnitView[];
  /** 권위 지형(대본의 구멍까지 반영). */
  mask: Mask | null;
  /** 스냅샷으로 지형을 통째로 바꿀 때마다 오른다(그리기 캐시 무효화). */
  maskVersion: number;
  turnSeat: number;
  turnNo: number;
  wind: number;
  cloudLevel: number;
  suddenDeathTurn: number;
  replay: Replay | null;
  result: MatchResult | null;
  turnClock: TurnClock | null;
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  /** 짧은 알림(넘김·시간 초과·밀물) — 낭독·상태 줄. */
  notice: { text: string; nonce: number } | null;
  /** 보낸 액션이 아직 답을 못 받았다(연타 방지). */
  pending: boolean;
}

export interface ArtilleryActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<ArtilleryTableView, ArtilleryPrivateView>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  setPending: (pending: boolean) => void;
  /** 재생을 끝까지 건너뛴다(탭이 숨었다 돌아옴·움직임 줄이기). */
  finishReplay: () => void;
}

const INITIAL: Omit<ArtilleryRoomState, 'disconnectedSeats'> = {
  roomId: null,
  phase: 'AIMING',
  units: [],
  mask: null,
  maskVersion: 0,
  turnSeat: -1,
  turnNo: 0,
  wind: 0,
  cloudLevel: 360,
  suddenDeathTurn: 0,
  replay: null,
  result: null,
  turnClock: null,
  errorMessage: null,
  notice: null,
  pending: false,
};

/**
 * 씨앗포 게임판 스토어 — `lastSeq` 없는 순수 리듀서(D-124). 순번 판정은 `useStompRoom` 이 한다.
 *
 * <p>권위 상태(포대·지형)는 이벤트를 받는 즉시 결과값으로 바꾸고, 화면은 {@link Replay} 로 대본을 다시 그린다. 그래서 재생 중에 resync 가
 * 와도 권위 상태만 바뀌고 재생은 끝 장면이 권위 상태와 같다.
 */
export const useArtilleryStore = create<ArtilleryRoomState & ArtilleryActions>((set, get) => ({
  ...INITIAL,
  disconnectedSeats: new Set(),

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    set({
      phase: t.phase,
      units: t.units,
      mask: maskFromRuns(t.terrain),
      maskVersion: get().maskVersion + 1,
      turnSeat: t.turnSeat,
      turnNo: t.turnNo,
      wind: t.wind,
      cloudLevel: t.cloudLevel,
      suddenDeathTurn: t.suddenDeathTurn,
      result: t.result,
      replay: null,
      pending: false,
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
      turnClock:
        t.phase === 'AIMING' && t.turnSeat >= 0
          ? { since: Date.now(), remainingMs: typeof snap.turnRemainingMs === 'number' ? snap.turnRemainingMs : null }
          : null,
    });
  },

  applyEvent(envelope) {
    const { type, payload } = envelope;
    const state = get();
    if (state.result && type !== 'PLAYER_DISCONNECTED' && type !== 'PLAYER_RECONNECTED') return 'ignored';

    switch (type) {
      case 'PLAYER_DISCONNECTED':
      case 'PLAYER_RECONNECTED': {
        const seat = (payload as { seat: number }).seat;
        const next = new Set(state.disconnectedSeats);
        if (type === 'PLAYER_DISCONNECTED') next.add(seat);
        else next.delete(seat);
        set({ disconnectedSeats: next });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as TurnChangedPayload;
        set({
          phase: 'AIMING',
          turnSeat: p.seat,
          turnNo: p.turnNo,
          wind: p.wind,
          cloudLevel: p.cloudLevel,
          pending: false,
          turnClock: { since: Date.now(), remainingMs: null },
        });
        return 'applied';
      }

      case 'SHOT_RESOLVED': {
        const report = (payload as { report: ShotReport }).report;
        const before: FrameUnit[] = state.units.map((u) => ({
          seat: u.seat,
          x: u.x,
          footY: u.footY,
          hp: u.hp,
          alive: u.alive,
        }));
        const maskBefore = state.mask;
        const crater = report.shot?.crater ?? null;
        const mask = maskBefore && crater ? carve(maskBefore, crater.x, crater.y, crater.r) : maskBefore;
        const outBy = new Map(report.eliminated.map((e) => [e.seat, e.cause]));
        const units = state.units.map((u) => {
          const after = report.units.find((x) => x.seat === u.seat);
          if (!after) return u;
          const pumpkins = u.seat === report.seat && report.shell === 'PUMPKIN' && report.shot ? u.pumpkins - 1 : u.pumpkins;
          return {
            ...u,
            x: after.x,
            footY: after.footY,
            hp: after.hp,
            alive: after.alive,
            pumpkins: Math.max(0, pumpkins),
            outCause: after.alive ? null : (outBy.get(u.seat) ?? u.outCause),
          };
        });
        set({
          phase: 'RESOLVING',
          turnSeat: -1,
          units,
          mask,
          pending: false,
          turnClock: null,
          replay: maskBefore
            ? { report, before, maskBefore, nonce: (state.replay?.nonce ?? 0) + 1 }
            : null,
        });
        return 'applied';
      }

      case 'TURN_SKIPPED': {
        const p = payload as TurnSkippedPayload;
        set({
          pending: false,
          notice: { text: p.reason === 'TIMEOUT' ? 'TIMEOUT' : 'SKIP', nonce: (state.notice?.nonce ?? 0) + 1 },
          ...(p.seat === state.turnSeat ? { turnClock: null } : {}),
        });
        return 'applied';
      }

      case 'UNIT_ELIMINATED': {
        const p = payload as UnitEliminatedPayload;
        set({
          cloudLevel: p.cloudLevel,
          units: state.units.map((u) => (u.seat === p.seat ? { ...u, alive: false, outCause: p.cause } : u)),
        });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({ phase: 'ENDED', result: p, turnSeat: -1, turnClock: null, pending: false });
        return 'applied';
      }

      default:
        return 'unhandled';
    }
  },

  setError(message) {
    set({ errorMessage: message, pending: false });
  },

  setPending(pending) {
    set({ pending });
  },

  finishReplay() {
    set({ replay: null });
  },
}));
