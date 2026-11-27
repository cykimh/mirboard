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

/** 낱말 최대 길이(§5.1) — 서버 `WordNormalizer.MAX_LENGTH` 와 같다. */
export const WORD_MAX = 10;

/**
 * 보내기 전 다듬기(§5.1 — 서버 `WordNormalizer` 와 <b>같은 규칙</b>, 서버가 정본이다): 유니코드 NFC(iOS 의 풀어 쓴 자모를 합친다) →
 * 공백·구분 문자·문장부호·물결표를 뺀다. 이모지·기호는 빼지 않는다(서버도 거절한다).
 */
export function normalizeWord(raw: string): string {
  return raw.normalize('NFC').replace(/[\s\p{Z}\p{P}~～]+/gu, '');
}

export interface WordCheck {
  /** 다듬은 낱말(보낼 값). */
  text: string;
  /** 지금 보낼 수 있는 꼴인가 — 서버가 받는 꼴(완성형 한글 1~10자)과 <b>정확히 같다</b>. 품사·고유 명사는 여기서 가리지 않는다(D-323). */
  ok: boolean;
  /** 못 보내는 까닭: 빈 값 `empty`, 자음·모음만 섞임 `jamo`(쓰는 중일 수 있다), 한글 아닌 글자 `chars`, 11자 이상 `long`. */
  problem: 'empty' | 'jamo' | 'chars' | 'long' | null;
}

/** 낱말이 지금 보낼 수 있는 꼴인지 — 버튼이 켜지는 조건 그 자체다(D-323). */
export function checkWord(raw: string): WordCheck {
  const text = normalizeWord(raw);
  if (!text) return { text, ok: false, problem: 'empty' };
  if (/^[가-힣]+$/.test(text)) {
    return text.length <= WORD_MAX ? { text, ok: true, problem: null } : { text, ok: false, problem: 'long' };
  }
  return { text, ok: false, problem: /^[가-힣ㄱ-ㅎㅏ-ㅣ]+$/.test(text) ? 'jamo' : 'chars' };
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
