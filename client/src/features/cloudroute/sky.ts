/**
 * 구름항로 하늘 시간대 (D-219) — 라운드가 지날수록 아침 → 낮 → 노을 → 밤. 화면 전용이다(서버·룰과 무관).
 *
 * - 라운드 제한이 있으면 판 전체를 넷으로 나눈다(마지막 구간이 밤).
 * - 제한이 없으면 {@link ROUNDS_PER_PHASE} 라운드마다 넘어가고, 밤 다음은 다시 아침이다.
 */
export type SkyPhase = 'morning' | 'day' | 'sunset' | 'night';

export const SKY_PHASES: readonly SkyPhase[] = ['morning', 'day', 'sunset', 'night'];

export const ROUNDS_PER_PHASE = 3;

export function skyPhaseOf(round: number, roundLimit: number): SkyPhase {
  const r = Math.max(1, Math.floor(round) || 1);
  if (roundLimit > 0) {
    const t = Math.min(1, (r - 1) / roundLimit);
    return SKY_PHASES[Math.min(3, Math.floor(t * 4))];
  }
  return SKY_PHASES[Math.floor((r - 1) / ROUNDS_PER_PHASE) % 4];
}
