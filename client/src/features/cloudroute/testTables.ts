import type { CloudRouteSeatView, CloudRouteTableView, CloudRouteTileView } from '@/types/cloudroute';
import { BOARD_SIZE } from './board';

/** 테스트용 공개 뷰 — 서버 `CloudRouteStateMapper.TableView` 모양. */
export function seatOf(seat: number, over: Partial<CloudRouteSeatView> = {}): CloudRouteSeatView {
  return {
    seat,
    cash: 1500,
    position: 0,
    trapped: false,
    failedEscapes: 0,
    holdsBuoy: false,
    eliminated: null,
    totalAssets: 1500,
    ...over,
  };
}

export function tilesOf(owned: Record<number, [number, number]> = {}): CloudRouteTileView[] {
  return Array.from({ length: BOARD_SIZE }, (_, tile) => ({
    tile,
    owner: owned[tile]?.[0] ?? -1,
    level: owned[tile]?.[1] ?? 0,
  }));
}

export function tableOf(over: Partial<CloudRouteTableView> = {}): CloudRouteTableView {
  return {
    phase: 'AWAIT_ROLL',
    seats: [seatOf(0), seatOf(1)],
    tiles: tilesOf(),
    festivalTile: -1,
    round: 1,
    roundLimit: 30,
    turnSeat: 0,
    doublesThisTurn: 0,
    pending: { kind: 'ROLL' },
    lastDice: [],
    deckCount: 10,
    discard: [],
    result: null,
    ...over,
  };
}

export function snapshotOf(table: CloudRouteTableView, over: { turnRemainingMs?: number | null; disconnectedSeats?: number[] } = {}) {
  return {
    roomId: 'r-1',
    phase: table.phase,
    eventSeq: 1,
    tableView: table,
    privateHand: null,
    disconnectedSeats: over.disconnectedSeats ?? [],
    chips: null,
    turnRemainingMs: over.turnRemainingMs ?? null,
  };
}
