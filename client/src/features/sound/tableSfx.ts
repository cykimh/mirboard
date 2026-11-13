import { getAudioCtx, isSfxMuted } from './useSfx';

/**
 * D-267 — 게임판 일반 동작 효과음(게임 중립). 전부 Web Audio 합성이고 외부 파일이 없다.
 *
 * <p>쓰는 법: 게임마다 순수 함수가 "공개 이벤트 + 적용 전후 상태 → {@link TableCueCall} 목록"을 정하고(`onecardSfx`·`omokSfx`),
 * 게임 sink 가 **실제 이벤트를 적용한 직후에만** {@link playCues} 를 부른다. resync 스냅샷·첫 렌더는 sink 의 다른 길이라 울리지 않는다.
 *
 * <p>재생 규칙은 여기 한 곳에 있다 — 음소거(`mirboard.sfxMuted`)를 따르고, 같은 소리는 {@link TABLE_SFX_MIN_GAP_MS} 안에 다시 울리지
 * 않으며, 동시에 {@link TABLE_SFX_MAX_VOICES} 개까지만 울린다(봇이 빠르게 둘 때 쌓이지 않게. 판 종료음은 예외). 소리는 연출 끄기·
 * 동작 줄이기와 무관하다(D-207 과 같다).
 */
export type TableCue =
  // 카드
  | 'card.play'
  | 'card.draw'
  | 'card.attack'
  | 'card.suit'
  | 'card.reverse'
  | 'card.skip'
  | 'card.again'
  | 'deck.shuffle'
  // 경쟁 창(원카드!/잡기!) — 짧게만. 목소리는 따로 얹힌다(D-265).
  | 'race.open'
  | 'race.called'
  | 'race.caught'
  // 돌
  | 'stone.place'
  | 'move.rejected'
  // 좌석·판
  | 'seat.out'
  | 'match.win'
  | 'match.lose'
  | 'match.draw'
  | 'match.resign';

export interface TableCueCall {
  cue: TableCue;
  /** 크기 배율(기본 1) — 남의 동작은 조금 작게. */
  gain?: number;
  /** `card.draw` — 먹은 장수(최대 {@link DRAW_REPEAT_MAX} 번 반복). */
  count?: number;
  /** `card.attack` — 세기 1~3. */
  level?: number;
  /** 이만큼 뒤에 울린다(초). */
  delaySec?: number;
}

/** 같은 소리가 다시 울리기까지의 최소 간격. */
export const TABLE_SFX_MIN_GAP_MS = 80;
/** 동시에 울리는 소리 수의 상한(판 종료음 `match.*` 은 세지만 막지 않는다). */
export const TABLE_SFX_MAX_VOICES = 3;
/** 여러 장 먹기의 반복 상한. */
export const DRAW_REPEAT_MAX = 4;
/** 전체 크기 — 작게 유지한다. */
const MASTER = 0.8;

interface ToneSpec {
  f: number;
  /** 있으면 이 주파수로 미끄러진다. */
  to?: number;
  at: number;
  dur: number;
  peak: number;
  type?: OscillatorType;
}

function tone(ctx: AudioContext, s: ToneSpec) {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = s.type ?? 'sine';
  osc.frequency.setValueAtTime(s.f, s.at);
  if (s.to !== undefined) osc.frequency.exponentialRampToValueAtTime(s.to, s.at + s.dur);
  gain.gain.setValueAtTime(0.0001, s.at);
  gain.gain.linearRampToValueAtTime(s.peak, s.at + 0.008);
  gain.gain.exponentialRampToValueAtTime(0.0001, s.at + s.dur);
  osc.connect(gain).connect(ctx.destination);
  osc.start(s.at);
  osc.stop(s.at + s.dur + 0.02);
}

interface NoiseSpec {
  at: number;
  dur: number;
  peak: number;
  filter: BiquadFilterType;
  f: number;
  to?: number;
}

/** 잡음 한 토막 — 빠르게 잦아드는 흰 잡음을 걸러 스침·두드림 소리로 쓴다. */
function noise(ctx: AudioContext, s: NoiseSpec) {
  const len = Math.max(1, Math.floor(ctx.sampleRate * s.dur));
  const buf = ctx.createBuffer(1, len, ctx.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len) ** 2;
  const src = ctx.createBufferSource();
  src.buffer = buf;
  const filter = ctx.createBiquadFilter();
  filter.type = s.filter;
  filter.frequency.setValueAtTime(s.f, s.at);
  if (s.to !== undefined) filter.frequency.exponentialRampToValueAtTime(s.to, s.at + s.dur);
  const gain = ctx.createGain();
  gain.gain.value = s.peak;
  src.connect(filter).connect(gain).connect(ctx.destination);
  src.start(s.at);
}

/** 소리 하나를 예약하고 길이(초)를 돌려준다. `g` 는 크기 배율, `t` 는 시작 시각. */
type Recipe = (ctx: AudioContext, t: number, g: number, call: TableCueCall) => number;

/** 여러 음을 `step` 간격으로. 마지막 음은 `lastDur` 만큼 길게. */
function notes(
  ctx: AudioContext,
  t: number,
  freqs: number[],
  step: number,
  dur: number,
  peak: number,
  type: OscillatorType = 'sine',
  lastDur = dur,
): number {
  freqs.forEach((f, i) =>
    tone(ctx, { f, at: t + i * step, dur: i === freqs.length - 1 ? lastDur : dur, peak, type }),
  );
  return (freqs.length - 1) * step + lastDur;
}

const RECIPES: Record<TableCue, Recipe> = {
  // 카드가 스치고(잡음이 높은 데서 낮은 데로) 판에 닿는다(낮은 톡).
  'card.play': (ctx, t, g) => {
    noise(ctx, { at: t, dur: 0.07, peak: 0.1 * g, filter: 'bandpass', f: 2600, to: 900 });
    tone(ctx, { f: 190, to: 120, at: t + 0.05, dur: 0.05, peak: 0.07 * g });
    return 0.1;
  },
  // 사각 — 장수만큼 짧게 반복(최대 4번).
  'card.draw': (ctx, t, g, call) => {
    const n = Math.max(1, Math.min(DRAW_REPEAT_MAX, Math.round(call.count ?? 1)));
    for (let i = 0; i < n; i++) {
      noise(ctx, { at: t + i * 0.07, dur: 0.05, peak: 0.07 * g, filter: 'bandpass', f: 3000, to: 1800 });
    }
    return (n - 1) * 0.07 + 0.05;
  },
  // 공격 카드 — 내기보다 묵직하게. 누적이 클수록 낮은 음이 하나씩 더 붙는다.
  'card.attack': (ctx, t, g, call) => {
    const level = Math.max(1, Math.min(3, Math.round(call.level ?? 1)));
    noise(ctx, { at: t, dur: 0.09, peak: 0.13 * g, filter: 'lowpass', f: 1100, to: 400 });
    for (let i = 0; i < level; i++) {
      tone(ctx, { f: 220 - i * 30, to: 110 - i * 15, at: t + i * 0.06, dur: 0.16, peak: 0.1 * g, type: 'triangle' });
    }
    return (level - 1) * 0.06 + 0.16;
  },
  // 7 무늬 바꾸기 — 톡 + 맑은 두 음.
  'card.suit': (ctx, t, g) => {
    tone(ctx, { f: 190, to: 120, at: t, dur: 0.05, peak: 0.06 * g });
    return 0.03 + notes(ctx, t + 0.03, [740, 988], 0.08, 0.1, 0.07 * g, 'sine', 0.16);
  },
  // 방향 바뀜 — 내려갔다 올라오는 미끄럼.
  'card.reverse': (ctx, t, g) => {
    tone(ctx, { f: 720, to: 420, at: t, dur: 0.1, peak: 0.07 * g, type: 'triangle' });
    tone(ctx, { f: 420, to: 720, at: t + 0.1, dur: 0.12, peak: 0.07 * g, type: 'triangle' });
    return 0.22;
  },
  // 건너뛰기 — 폴짝 뛰는 두 음.
  'card.skip': (ctx, t, g) => notes(ctx, t, [520, 780], 0.07, 0.05, 0.07 * g, 'triangle', 0.08),
  // 한 번 더 — 같은 음 두 번 + 한 음 위.
  'card.again': (ctx, t, g) => notes(ctx, t, [523.25, 523.25, 659.25], 0.07, 0.05, 0.07 * g, 'triangle', 0.1),
  // 더미 다시 섞기 — 잘게 여러 번 사각사각.
  'deck.shuffle': (ctx, t, g) => {
    for (let i = 0; i < 6; i++) {
      noise(ctx, { at: t + i * 0.045, dur: 0.04, peak: 0.06 * g, filter: 'bandpass', f: 2000 + (i % 2) * 700 });
    }
    return 0.27;
  },
  // 경쟁 창 열림 — 위로 튀는 짧은 신호.
  'race.open': (ctx, t, g) => {
    tone(ctx, { f: 880, to: 1320, at: t, dur: 0.08, peak: 0.08 * g, type: 'triangle' });
    tone(ctx, { f: 1320, at: t + 0.09, dur: 0.07, peak: 0.07 * g, type: 'triangle' });
    return 0.16;
  },
  'race.called': (ctx, t, g) => notes(ctx, t, [988, 1318.5], 0.07, 0.07, 0.08 * g, 'triangle', 0.14),
  // 잡힘 — 툭 떨어진다.
  'race.caught': (ctx, t, g) => {
    noise(ctx, { at: t, dur: 0.05, peak: 0.1 * g, filter: 'lowpass', f: 900 });
    tone(ctx, { f: 600, to: 300, at: t, dur: 0.16, peak: 0.09 * g, type: 'triangle' });
    return 0.16;
  },
  // 나무 판에 돌 — 둔탁한 "딱"(걸러 낸 잡음 + 빠르게 떨어지는 낮은 음 + 아주 짧은 딸깍).
  'stone.place': (ctx, t, g) => {
    noise(ctx, { at: t, dur: 0.05, peak: 0.22 * g, filter: 'lowpass', f: 1500 });
    tone(ctx, { f: 320, to: 180, at: t, dur: 0.06, peak: 0.16 * g });
    tone(ctx, { f: 900, to: 500, at: t, dur: 0.025, peak: 0.05 * g, type: 'triangle' });
    return 0.08;
  },
  // 둘 수 없는 자리 — 낮고 짧은 "툭".
  'move.rejected': (ctx, t, g) => {
    tone(ctx, { f: 150, to: 95, at: t, dur: 0.11, peak: 0.16 * g });
    return 0.11;
  },
  'seat.out': (ctx, t, g) => notes(ctx, t, [440, 349.23, 261.63], 0.1, 0.11, 0.08 * g, 'sine', 0.2),
  'match.win': (ctx, t, g) => notes(ctx, t, [523.25, 659.25, 783.99, 1046.5], 0.09, 0.12, 0.1 * g, 'triangle', 0.4),
  'match.lose': (ctx, t, g) => notes(ctx, t, [392, 329.63, 261.63], 0.16, 0.2, 0.07 * g, 'sine', 0.32),
  'match.draw': (ctx, t, g) => notes(ctx, t, [440, 440], 0.18, 0.16, 0.06 * g, 'sine', 0.26),
  // 기권·시간패·나감 — 돌통을 덮는 낮은 소리.
  'match.resign': (ctx, t, g) => {
    noise(ctx, { at: t, dur: 0.06, peak: 0.1 * g, filter: 'lowpass', f: 600 });
    tone(ctx, { f: 170, to: 90, at: t, dur: 0.2, peak: 0.12 * g });
    return 0.2;
  },
};

// ── 재생 규칙 상태(모듈 전역 — 게임판은 한 번에 하나만 떠 있다) ──
const lastAt = new Map<TableCue, number>();
let voiceEnds: number[] = [];

/** 테스트용 — 간격·동시 재생 기록을 비운다. */
export function resetTableSfx() {
  lastAt.clear();
  voiceEnds = [];
}

/**
 * 소리 하나를 울린다. 울렸으면 true — 음소거·Web Audio 없음·간격 미달·동시 재생 상한이면 false(조용히).
 */
export function playTableSfx(cue: TableCue, opts: Omit<TableCueCall, 'cue'> = {}): boolean {
  if (isSfxMuted()) return false;
  const now = Date.now();
  const last = lastAt.get(cue);
  if (last !== undefined && now - last < TABLE_SFX_MIN_GAP_MS) return false;
  voiceEnds = voiceEnds.filter((end) => end > now);
  if (voiceEnds.length >= TABLE_SFX_MAX_VOICES && !cue.startsWith('match.')) return false;
  const ctx = getAudioCtx();
  if (!ctx) return false;
  try {
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    // 사용자 동작 전(새 탭·새로고침 직후)이면 컨텍스트가 멈춰 있다 — 그대로 예약하면 시계가 멈춘 채 쌓였다가 첫 탭에 한꺼번에 터지고,
    // 관전자는 노드가 계속 쌓인다(리뷰 지적). 돌고 있을 때만 울린다.
    if (ctx.state !== 'running') return false;
    const delay = Math.max(0, opts.delaySec ?? 0);
    const call: TableCueCall = { cue, ...opts };
    const dur = RECIPES[cue](ctx, ctx.currentTime + delay, MASTER * (opts.gain ?? 1), call);
    lastAt.set(cue, now);
    voiceEnds.push(now + (delay + dur) * 1000);
    return true;
  } catch {
    // 합성 실패 — silent.
    return false;
  }
}

/** 이벤트 하나가 낸 소리들을 순서대로 울린다. */
export function playCues(cues: readonly TableCueCall[]) {
  for (const { cue, ...opts } of cues) playTableSfx(cue, opts);
}
