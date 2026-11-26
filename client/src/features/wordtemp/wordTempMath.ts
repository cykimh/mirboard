import type { PublicAttempt } from '@/types/wordtemp';

/** 온도의 띠(`docs/rules-wordtemp.md` §1) — 화면 색·소리·문구가 같이 쓴다. */
export type TempBand = 'cold' | 'warm' | 'hot' | 'boil';

/** 96 이상 "거의 정답" — 힌트 단계가 그 자리에서 끝난다(§3.2). */
export const BOIL_FROM = 96;
export const HOT_FROM = 75;
export const WARM_FROM = 40;

export function tempBand(temp: number): TempBand {
  if (temp >= BOIL_FROM) return 'boil';
  if (temp >= HOT_FROM) return 'hot';
  if (temp >= WARM_FROM) return 'warm';
  return 'cold';
}

export const BAND_LABEL: Record<TempBand, string> = {
  cold: '차가움',
  warm: '미지근',
  hot: '뜨거움',
  boil: '거의 정답',
};

/**
 * 차수별 최저 온도(§3.4 — 1차 50 · 2차 60 · 3차 70). <b>표시용</b>이다 — 판정은 서버가 하고 공개 힌트마다 `floor`·`floorMet` 을 실어 준다.
 * 공개 전에는 서버가 이번 차수의 최저 온도를 알려 주지 않아, 출제자에게 미리 보여 주는 눈금만 이 값을 쓴다(룰 수치를 바꾸면 같이 고친다).
 */
export const ATTEMPT_FLOORS: readonly number[] = [50, 60, 70];

export function floorOf(attempt: number): number {
  return ATTEMPT_FLOORS[Math.max(0, Math.min(ATTEMPT_FLOORS.length - 1, attempt - 1))];
}

/** 차수 최대(§1). */
export const MAX_ATTEMPTS = 3;

/**
 * 보내기 전 다듬기(§5.1 의 앞 두 걸음 — 서버가 정본이다): 유니코드 NFC(iOS 의 풀어 쓴 자모를 합친다) + 공백 제거.
 * 한글 여부·길이는 서버가 본다(거절 사유를 문구로 보여 준다).
 */
export function normalizeWord(raw: string): string {
  return raw.normalize('NFC').replace(/\s+/g, '');
}

/** 남은 시간 글자 — 1분 넘으면 `1:05`, 아니면 초. */
export function clockText(ms: number): string {
  const sec = Math.max(0, Math.ceil(ms / 1000));
  if (sec >= 60) return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
  return `${sec}`;
}

/** 그 차수에 좌석마다 얻은 점수(§3.4) — 맞힌 사람 +1×r, 출제자는 `setterGain`. 스냅샷으로 받은 결과 화면이 쓴다. */
export function attemptDeltas(attempt: PublicAttempt, setterSeat: number, round: number): Record<number, number> {
  const out: Record<number, number> = {};
  for (const g of attempt.guesses) if (g.correct) out[g.seat] = round;
  if (attempt.setterGain > 0) out[setterSeat] = attempt.setterGain;
  return out;
}

/**
 * 후보 낱말의 범주 — 서버는 정답 은행의 범주 코드(`animal`·`food` …)를 그대로 보낸다. 화면 이름만 여기서 붙인다(모르는 코드는 그대로 보인다).
 */
const CATEGORY_LABEL: Readonly<Record<string, string>> = {
  animal: '동물',
  appliance: '가전',
  art: '예술',
  body: '몸',
  clothing: '옷',
  food: '음식',
  fruitveg: '과일·채소',
  household: '생활용품',
  job: '직업',
  kitchen: '주방',
  nature: '자연',
  place: '장소',
  school: '학교',
  sports: '운동',
  vehicle: '탈것',
};

export function categoryLabel(code: string): string {
  return Object.prototype.hasOwnProperty.call(CATEGORY_LABEL, code) ? CATEGORY_LABEL[code] : code;
}
