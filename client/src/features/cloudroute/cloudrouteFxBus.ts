import type { CloudRouteLetter, CloudRouteSeatView, CloudRouteTileView } from '@/types/cloudroute';
import type { CloudRouteRoomState, LogEntry } from './cloudrouteStore';

/**
 * 구름항로 연출 버스 (D-218). sink 가 스토어에 **이미 적용한** 이벤트를 "적용 전·후 화면 값"과 함께 여기로 넘기고, 게임판의 연출 감독
 * (`useCloudRouteFx`)이 받아 순서대로 재생한다. 스토어·서버·룰은 이 버스를 모른다 — 감독이 붙어 있지 않으면(테스트·움직임 줄이기·기본
 * 테마) 아무것도 쌓지 않는다.
 */

/** 연출이 따라 그리는 화면 값 — 스토어 상태에서 그대로 집은 참조(복사 없음, 스토어는 불변 갱신이다). */
export interface FxView {
  seats: CloudRouteSeatView[];
  tiles: CloudRouteTileView[];
  festivalTile: number;
  lastDice: number[];
  lastLetter: CloudRouteLetter | null;
  turnSeat: number;
  round: number;
  deckCount: number;
  log: LogEntry[];
}

export interface FxItem {
  type: string;
  payload: unknown;
  before: FxView;
  after: FxView;
}

export function fxViewOf(s: CloudRouteRoomState): FxView {
  return {
    seats: s.seats,
    tiles: s.tiles,
    festivalTile: s.festivalTile,
    lastDice: s.lastDice,
    lastLetter: s.lastLetter,
    turnSeat: s.turnSeat,
    round: s.round,
    deckCount: s.deckCount,
    log: s.log,
  };
}

type Handler = (item: FxItem | 'clear') => void;

let handler: Handler | null = null;

export const cloudrouteFxBus = {
  /** 감독이 붙어 있는가 — 아니면 sink 는 화면 값을 집지도 않는다. */
  get attached(): boolean {
    return handler !== null;
  },
  attach(h: Handler) {
    handler = h;
  },
  detach(h: Handler) {
    if (handler === h) handler = null;
  },
  emit(item: FxItem) {
    handler?.(item);
  },
  /** 권위 스냅샷·방 초기화 — 남은 연출을 버리고 진짜 값으로 돌아간다. */
  clear() {
    handler?.('clear');
  },
};
