import type { HeadToHeadEntry } from '@/api/users';

/** D-154 — 상대 전적 한 줄의 승·패(무가 있으면 무까지). */
export function headToHeadText(e: Pick<HeadToHeadEntry, 'wins' | 'losses' | 'draws'>): string {
  return `${e.wins}승 ${e.losses}패${e.draws > 0 ? ` ${e.draws}무` : ''}`;
}
