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
