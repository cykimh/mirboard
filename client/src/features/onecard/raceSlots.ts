/**
 * 경쟁 버튼 슬롯 — **프로토콜 상수**다. 서버는 창마다 `slot`(0..7)과 `jitterX`·`jitterY`(−100..100, 슬롯 반경의
 * 백분율)를 골라 전원에게 같은 값을 보낸다(`docs/stomp-protocol.md` 원카드 절). 전원이 같은 위치를 받으므로 위치 운은
 * 공평하다(설계서 §4.4).
 *
 * <p>좌표는 **화면(뷰포트) 기준 %**다. 게임판은 세로로 길어 모바일에서 스크롤되므로 게임판 기준으로 두면 버튼이 화면
 * 밖에 뜰 수 있다. 손패와 버튼 줄이 놓이는 아래쪽(대략 60% 아래)과 화면 가장자리를 피한다. 이 표를 바꾸면 모든
 * 클라의 위치가 함께 바뀌므로 서버와 맞출 것은 없지만, 개수(8)는 서버 `RaceSettings` 와 같아야 한다.
 */
export const RACE_SLOT_COUNT = 8;

export const RACE_SLOTS: ReadonlyArray<{ readonly x: number; readonly y: number }> = [
  { x: 22, y: 18 },
  { x: 50, y: 14 },
  { x: 78, y: 18 },
  { x: 16, y: 38 },
  { x: 84, y: 38 },
  { x: 36, y: 52 },
  { x: 64, y: 52 },
  { x: 50, y: 30 },
];

/** 지터가 버튼을 옮기는 최대 거리 — 뷰포트 % (가로, 세로). */
export const RACE_JITTER_RADIUS = { x: 10, y: 6 } as const;

function clampJitter(v: number): number {
  return Math.max(-100, Math.min(100, v));
}

/** 슬롯 + 지터 → 버튼 중심의 뷰포트 % 좌표. 범위 밖 값은 잘라 쓴다(계약 밖 방어). */
export function racePosition(
  slot: number,
  jitterX: number,
  jitterY: number,
): { left: number; top: number } {
  const base = RACE_SLOTS[((slot % RACE_SLOT_COUNT) + RACE_SLOT_COUNT) % RACE_SLOT_COUNT];
  return {
    left: base.x + (clampJitter(jitterX) / 100) * RACE_JITTER_RADIUS.x,
    top: base.y + (clampJitter(jitterY) / 100) * RACE_JITTER_RADIUS.y,
  };
}

/**
 * 버튼 중심의 가로 위치(`left`) CSS — 가장자리에서 버튼 반폭(`--oc-race-half-w`)만큼 안쪽으로 보정한다. 슬롯 표만으로는 버튼
 * *중심*이 5~95% 라 좁은 화면에서 버튼이 가장자리에서 잘릴 수 있다. 변수는 버튼을 담는 쪽(`.oc-race-layer`·튜토리얼
 * 연습 칸 `.oc-practice-area`)이 정의하고, 두 곳이 같은 식을 쓴다.
 */
export function raceLeftCss(leftPercent: number): string {
  return `clamp(var(--oc-race-half-w), ${leftPercent}%, calc(100% - var(--oc-race-half-w)))`;
}
