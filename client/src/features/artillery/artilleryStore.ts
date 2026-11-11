import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import type {
  AimPreviewPayload,
  ArtilleryPrivateView,
  ArtilleryTableView,
  DelayTable,
  MatchEndedPayload,
  MatchResult,
  Phase,
  ShotReport,
  TurnChangedPayload,
  TurnSkippedPayload,
  UnitEliminatedPayload,
  UnitKind,
  UnitMovedPayload,
  UnitPickedPayload,
  UnitsRevealedPayload,
  UnitView,
} from '@/types/artillery';
import { applyOp, maskFromRuns, type Mask } from './terrain';
import { terrainOps, type FrameUnit } from './replay';
import type { WalkFollow } from './liveMove';

/** 남의 조준 미리보기(D-229) — 받은 시각과 함께. 지금 차례 것만 둔다. */
export interface AimPreview extends AimPreviewPayload {
  at: number;
}

/** 재생 중인 발사 — 게임판이 시작 시각부터 `frameAt` 으로 그린다. */
export interface Replay {
  report: ShotReport;
  before: FrameUnit[];
  /** 대본 이전 지형 = {@code masks[0]}. */
  maskBefore: Mask;
  /** {@code masks[k]} = 대본의 지형 연산을 앞에서 k 개 적용한 지형(재생이 씨앗이 내려앉는 대로 고른다). */
  masks: Mask[];
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
  /** 고르기 단계(D-224) — 남은 시간 기준점과 내가 고른 종류. */
  pickClock: TurnClock | null;
  myPick: UnitKind | null;
  /** 2:2 팀전(D-225) — 팀 = 좌석 홀짝. */
  teams: boolean;
  /** 앞으로의 차례 순서(D-226) — 지금 차례 주인부터. */
  queue: number[];
  /** 딜레이 표(D-226) — 구 서버면 null(안내를 숨긴다). */
  delayTable: DelayTable | null;
  /** 차례 주인의 남은 연료(D-228 — `MOVE` 로 쓴 만큼 줄었다). null 이면 그 포대 연료 가득. */
  fuelLeft: number | null;
  /** 남의 걸음 따라가기(D-251) — 마지막 `UNIT_MOVED`. */
  follow: WalkFollow | null;
  /** 마지막 `UNIT_MOVED` 의 좌석과 번호 — 내 `MOVE` 응답을 알아보는 데 쓴다. */
  lastMove: { seat: number; nonce: number } | null;
  /** 차례 주인의 조준 미리보기(D-229). */
  aimPreview: AimPreview | null;
  /** 보낸 `MOVE` 가 답을 기다린다 — 그 사이의 `BUSY`·`CANNOT_MOVE` 는 띠를 띄우지 않고 {@link lastError} 로만 남긴다. */
  moveInFlight: boolean;
  lastError: { code: string; nonce: number } | null;
  /** resync 마다 오른다 — 로컬에 모아 둔 걸음을 버리는 기준. */
  snapshotNonce: number;
}

export interface ArtilleryActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<ArtilleryTableView, ArtilleryPrivateView>) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  setPending: (pending: boolean) => void;
  /** 재생을 끝까지 건너뛴다(탭이 숨었다 돌아옴·움직임 줄이기). */
  finishReplay: () => void;
  /** 내가 고른 종류(보낸 직후 — 서버는 다 고를 때까지 남에게 숨긴다). */
  setMyPick: (kind: UnitKind) => void;
  setMoveInFlight: (inFlight: boolean) => void;
  /** 거절 코드 기록(D-251) — 걷기 보내기가 자기 `MOVE` 의 거절을 알아본다. */
  noteError: (code: string) => void;
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
  pickClock: null,
  myPick: null,
  teams: false,
  queue: [],
  delayTable: null,
  fuelLeft: null,
  follow: null,
  lastMove: null,
  aimPreview: null,
  moveInFlight: false,
  lastError: null,
  snapshotNonce: 0,
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
      teams: t.teams ?? false,
      queue: t.queue ?? [],
      delayTable: t.delays ?? null,
      replay: null,
      pending: false,
      pickClock:
        t.phase === 'PICKING' ? { since: Date.now(), remainingMs: t.pickRemainingMs ?? null } : null,
      myPick: t.phase === 'PICKING' ? (snap.privateHand?.pickedKind ?? null) : null,
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
      // D-228 — 엔진이 센 차례 전체 남은 시간이 우선(바깥 값은 마지막 액션 기준이라 MOVE 뒤엔 틀린다). 구 서버면 바깥 값.
      turnClock:
        t.phase === 'AIMING' && t.turnSeat >= 0
          ? {
              since: Date.now(),
              remainingMs:
                typeof t.turnRemainingMs === 'number'
                  ? t.turnRemainingMs
                  : typeof snap.turnRemainingMs === 'number'
                    ? snap.turnRemainingMs
                    : null,
            }
          : null,
      fuelLeft: t.phase === 'AIMING' && t.turnSeat >= 0 && typeof t.fuel === 'number' ? t.fuel : null,
      follow: null,
      aimPreview: null,
      moveInFlight: false,
      snapshotNonce: get().snapshotNonce + 1,
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

      case 'UNIT_PICKED': {
        const p = payload as UnitPickedPayload;
        set({ units: state.units.map((u) => (u.seat === p.seat ? { ...u, picked: true } : u)) });
        return 'applied';
      }

      case 'UNITS_REVEALED': {
        const p = payload as UnitsRevealedPayload;
        set({ units: p.units, phase: 'AIMING', pickClock: null, myPick: null, pending: false });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as TurnChangedPayload;
        set({
          pickClock: null,
          phase: 'AIMING',
          turnSeat: p.seat,
          turnNo: p.turnNo,
          wind: p.wind,
          cloudLevel: p.cloudLevel,
          pending: false,
          turnClock: { since: Date.now(), remainingMs: null },
          fuelLeft: null,
          follow: null,
          aimPreview: null,
          queue: p.queue ?? state.queue,
          units: p.delays
            ? state.units.map((u) => (p.delays && p.delays[u.seat] !== undefined ? { ...u, delay: p.delays[u.seat] } : u))
            : state.units,
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
          kind: u.kind,
          maxHp: u.maxHp,
        }));
        const maskBefore = state.mask;
        const masks: Mask[] = [];
        if (maskBefore) {
          masks.push(maskBefore);
          for (const op of terrainOps(report)) masks.push(applyOp(masks[masks.length - 1], op));
        }
        const mask = masks.length > 0 ? masks[masks.length - 1] : maskBefore;
        const fired = report.stages.length > 0;
        const outBy = new Map(report.eliminated.map((e) => [e.seat, e.cause]));
        const units = state.units.map((u) => {
          const after = report.units.find((x) => x.seat === u.seat);
          if (!after) return u;
          const mine = u.seat === report.seat;
          const pumpkins = mine && fired && report.shell === 'PUMPKIN' ? u.pumpkins - 1 : u.pumpkins;
          const specials = mine && fired && report.shell === u.special ? u.specials - 1 : u.specials;
          const items = mine && report.item ? u.items.filter((it) => it !== report.item) : u.items;
          return {
            ...u,
            x: after.x,
            footY: after.footY,
            hp: after.hp,
            alive: after.alive,
            pumpkins: Math.max(0, pumpkins),
            specials: Math.max(0, specials),
            items,
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
          fuelLeft: null,
          follow: null,
          aimPreview: null,
          replay: maskBefore
            ? { report, before, maskBefore, masks, nonce: (state.replay?.nonce ?? 0) + 1 }
            : null,
        });
        return 'applied';
      }

      case 'UNIT_MOVED': {
        // D-228 — 걷기 확정(결과값). 탈락이면 그 차례가 끝나고 대본 없는 연출 대기(RESOLVING, lastShot 없음)가 이어진다.
        const p = payload as UnitMovedPayload;
        const before = state.units.find((u) => u.seat === p.seat);
        const out = p.eliminated;
        const path = p.fall ? [...p.path, p.toX, p.toY] : p.path;
        set({
          units: state.units.map((u) =>
            u.seat === p.seat
              ? { ...u, x: p.toX, footY: p.toY, hp: p.hpAfter, alive: out === null, outCause: out ?? u.outCause }
              : u,
          ),
          fuelLeft: out === null ? p.fuelLeft : null,
          follow: before
            ? { seat: p.seat, fromX: before.x, fromY: before.footY, path, startedAt: Date.now(), nonce: (state.follow?.nonce ?? 0) + 1 }
            : null,
          lastMove: { seat: p.seat, nonce: (state.lastMove?.nonce ?? 0) + 1 },
          ...(out !== null
            ? {
                phase: 'RESOLVING' as Phase,
                turnSeat: -1,
                turnClock: null,
                aimPreview: null,
                pending: false,
                notice: { text: out === 'CLOUD_SEA' ? 'FELL' : 'WILTED', nonce: (state.notice?.nonce ?? 0) + 1 },
              }
            : {}),
        });
        return 'applied';
      }

      case 'AIM_PREVIEW': {
        // D-229 — 순번 없음. 지금 차례 주인의 것이고 그 차례 안에서 더 새 번호일 때만(락 없이 읽은 낡은 프레임을 거른다).
        const p = payload as AimPreviewPayload;
        const prev = state.aimPreview;
        const fresh =
          state.phase === 'AIMING' &&
          state.replay === null &&
          p.seat === state.turnSeat &&
          p.turn === state.turnNo &&
          (prev === null || prev.turn !== p.turn || prev.seat !== p.seat || p.n > prev.n);
        if (!fresh) return 'ignored';
        set({ aimPreview: { ...p, at: Date.now() } });
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

  setMyPick(kind) {
    set({ myPick: kind });
  },

  setMoveInFlight(inFlight) {
    if (get().moveInFlight !== inFlight) set({ moveInFlight: inFlight });
  },

  noteError(code) {
    set({ lastError: { code, nonce: (get().lastError?.nonce ?? 0) + 1 } });
  },
}));
