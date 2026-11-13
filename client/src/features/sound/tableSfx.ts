import { getAudioCtx, isSfxMuted, sfxOut } from './useSfx';

/**
 * D-267 — 게임판 일반 동작 효과음(게임 중립). 전부 Web Audio 합성이고 외부 파일이 없다. 소리 성격은 D-271(밝고 통통 튀는 캐주얼 게임).
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
  // 주사위·점수표·버튼(D-268 얏추) — 누른 사람 손끝의 짧은 "뽁".
  | 'die.hold'
  | 'die.release'
  | 'score.commit'
  | 'ui.tap'
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
/** 전체 크기(D-271 — 0.8 에서 올렸다. 겹칠 때의 꼭대기는 `sfxOut` 의 압축기가 누른다). */
const MASTER = 1;

interface ToneSpec {
  f: number;
  /** 있으면 이 주파수로 미끄러진다. */
  to?: number;
  /** 미끄러지는 데 걸리는 시간(초). 없으면 음 길이 전체 — 짧게 주면 "뽁" 하고 튀는 소리가 된다. */
  glide?: number;
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
  if (s.to !== undefined) osc.frequency.exponentialRampToValueAtTime(s.to, s.at + (s.glide ?? s.dur));
  gain.gain.setValueAtTime(0.0001, s.at);
  gain.gain.linearRampToValueAtTime(s.peak, s.at + 0.008);
  gain.gain.exponentialRampToValueAtTime(0.0001, s.at + s.dur);
  osc.connect(gain).connect(sfxOut(ctx));
  osc.start(s.at);
  osc.stop(s.at + s.dur + 0.02);
}

/** "뽁" — 아래에서 `f` 로 빠르게 튀어 오르는 둥근 사인 한 방울(캐주얼 게임의 기본 클릭). */
function pop(ctx: AudioContext, at: number, f: number, peak: number, dur = 0.07) {
  tone(ctx, { f: f * 0.62, to: f, glide: Math.min(0.035, dur * 0.5), at, dur, peak });
}

/** 마림바처럼 통통 튀는 한 음 — 바탕음 + 짧게 꺼지는 두 옥타브 위 배음. */
function pluck(ctx: AudioContext, at: number, f: number, peak: number, dur: number) {
  tone(ctx, { f, at, dur, peak });
  tone(ctx, { f: f * 4, at, dur: Math.max(0.03, dur * 0.3), peak: peak * 0.22 });
}

/** 소리 하나를 예약하고 길이(초)를 돌려준다. `g` 는 크기 배율, `t` 는 시작 시각. */
type Recipe = (ctx: AudioContext, t: number, g: number, call: TableCueCall) => number;

/** 통통 튀는 음 여럿을 `step` 간격으로. 마지막 음은 `lastDur` 만큼 길게. */
function plucks(ctx: AudioContext, t: number, freqs: number[], step: number, dur: number, peak: number, lastDur = dur): number {
  freqs.forEach((f, i) => pluck(ctx, t + i * step, f, peak, i === freqs.length - 1 ? lastDur : dur));
  return (freqs.length - 1) * step + lastDur;
}

/**
 * D-271 — 소리 성격은 밝고 통통 튀는 캐주얼 게임이다: 잡음(스침·딸깍) 대신 둥근 사인 "뽁"·마림바풍 "통"·미끄럼 휘파람을 쓴다.
 * 거친 파형(sawtooth·square)과 흰 잡음은 쓰지 않는다.
 */
const RECIPES: Record<TableCue, Recipe> = {
  // 카드 내기 — 둥근 "뽁" + 아주 작은 윗소리.
  'card.play': (ctx, t, g) => {
    pop(ctx, t, 660, 0.17 * g, 0.09);
    tone(ctx, { f: 1320, to: 990, at: t, dur: 0.03, peak: 0.045 * g, type: 'triangle' });
    return 0.09;
  },
  // 먹기 — 위로 미끄러지는 짧은 휘파람. 장수만큼 한 음씩 높여 반복(최대 4번).
  'card.draw': (ctx, t, g, call) => {
    const n = Math.max(1, Math.min(DRAW_REPEAT_MAX, Math.round(call.count ?? 1)));
    for (let i = 0; i < n; i++) {
      tone(ctx, { f: 520 + i * 70, to: 980 + i * 130, at: t + i * 0.08, dur: 0.1, peak: 0.13 * g });
    }
    return (n - 1) * 0.08 + 0.1;
  },
  // 공격 카드 — 통통한 "보잉". 누적이 클수록 낮은 음이 하나씩 더 붙는다.
  'card.attack': (ctx, t, g, call) => {
    const level = Math.max(1, Math.min(3, Math.round(call.level ?? 1)));
    pop(ctx, t, 520, 0.12 * g, 0.06);
    for (let i = 0; i < level; i++) {
      tone(ctx, { f: 330 - i * 45, to: 165 - i * 22, at: t + i * 0.07, dur: 0.18, peak: 0.21 * g, type: 'triangle' });
    }
    return (level - 1) * 0.07 + 0.18;
  },
  // 7 무늬 바꾸기 — 뽁 + 반짝이는 세 음.
  'card.suit': (ctx, t, g) => {
    pop(ctx, t, 587.33, 0.13 * g, 0.06);
    return 0.04 + plucks(ctx, t + 0.04, [783.99, 987.77, 1318.51], 0.06, 0.09, 0.12 * g, 0.18);
  },
  // 방향 바뀜 — 내려갔다 올라오는 미끄럼 휘파람.
  'card.reverse': (ctx, t, g) => {
    tone(ctx, { f: 920, to: 460, at: t, dur: 0.11, peak: 0.13 * g });
    tone(ctx, { f: 460, to: 980, at: t + 0.1, dur: 0.14, peak: 0.13 * g });
    return 0.24;
  },
  // 건너뛰기 — 폴짝 뛰는 두 방울.
  'card.skip': (ctx, t, g) => {
    pop(ctx, t, 587.33, 0.14 * g, 0.07);
    pop(ctx, t + 0.08, 880, 0.14 * g, 0.1);
    return 0.18;
  },
  // 한 번 더 — 같은 음 두 번 + 한 음 위.
  'card.again': (ctx, t, g) => plucks(ctx, t, [659.25, 659.25, 880], 0.07, 0.07, 0.13 * g, 0.14),
  // 더미 다시 섞기 — 보글보글 여섯 방울.
  'deck.shuffle': (ctx, t, g) => {
    for (let i = 0; i < 6; i++) pop(ctx, t + i * 0.045, 520 + ((i * 2) % 5) * 90, 0.1 * g, 0.045);
    return 0.27;
  },
  // 경쟁 창 열림 — 위로 튀는 "삐빅!".
  'race.open': (ctx, t, g) => {
    tone(ctx, { f: 740, to: 1480, at: t, dur: 0.08, peak: 0.16 * g, type: 'triangle' });
    tone(ctx, { f: 880, to: 1760, at: t + 0.09, dur: 0.09, peak: 0.16 * g, type: 'triangle' });
    return 0.18;
  },
  'race.called': (ctx, t, g) => plucks(ctx, t, [987.77, 1318.51, 1567.98], 0.06, 0.08, 0.14 * g, 0.18),
  // 잡힘 — "뿌우" 미끄러져 내려와 툭.
  'race.caught': (ctx, t, g) => {
    tone(ctx, { f: 700, to: 260, at: t, dur: 0.18, peak: 0.15 * g, type: 'triangle' });
    tone(ctx, { f: 196, to: 130, at: t + 0.15, dur: 0.1, peak: 0.16 * g });
    return 0.25;
  },
  // 돌 놓기 — 나무 실로폰을 치는 둥근 "똑"(빠르게 떨어지는 사인 + 짧은 윗소리).
  'stone.place': (ctx, t, g) => {
    tone(ctx, { f: 640, to: 400, at: t, dur: 0.08, peak: 0.27 * g });
    tone(ctx, { f: 1280, to: 960, at: t, dur: 0.03, peak: 0.08 * g, type: 'triangle' });
    return 0.08;
  },
  // 둘 수 없는 자리 — 낮고 순한 "봉-봉"(두 번).
  'move.rejected': (ctx, t, g) => {
    tone(ctx, { f: 190, to: 135, at: t, dur: 0.09, peak: 0.22 * g, type: 'triangle' });
    tone(ctx, { f: 160, to: 105, at: t + 0.09, dur: 0.13, peak: 0.22 * g, type: 'triangle' });
    return 0.22;
  },
  // 주사위 고정 — 아래로 내려앉는 "뽀".
  'die.hold': (ctx, t, g) => {
    tone(ctx, { f: 760, to: 470, glide: 0.05, at: t, dur: 0.08, peak: 0.17 * g });
    return 0.08;
  },
  // 고정 풀기 — 같은 소리가 위로 튄다.
  'die.release': (ctx, t, g) => {
    tone(ctx, { f: 470, to: 790, glide: 0.05, at: t, dur: 0.07, peak: 0.15 * g });
    return 0.07;
  },
  // 점수 칸에 적기 — 맑게 통통 "딩-동!"(다섯 도 위로).
  'score.commit': (ctx, t, g) => plucks(ctx, t, [783.99, 1174.66], 0.07, 0.08, 0.15 * g, 0.18),
  // 그 밖의 버튼 — 작은 "뽁" 하나.
  'ui.tap': (ctx, t, g) => {
    pop(ctx, t, 780, 0.13 * g, 0.055);
    return 0.055;
  },
  // 탈락 — 축 처지는 미끄럼 휘파람 + 작은 툭.
  'seat.out': (ctx, t, g) => {
    tone(ctx, { f: 620, to: 230, at: t, dur: 0.3, peak: 0.14 * g });
    tone(ctx, { f: 175, to: 120, at: t + 0.28, dur: 0.1, peak: 0.14 * g });
    return 0.38;
  },
  // 이김 — 위로 달리는 반짝 아르페지오 + 높은 반짝임 셋.
  'match.win': (ctx, t, g) => {
    const run = plucks(ctx, t, [523.25, 659.25, 783.99, 1046.5, 1318.51, 1567.98], 0.075, 0.12, 0.17 * g, 0.45);
    [2093, 2637.02, 3135.96].forEach((f, i) => tone(ctx, { f, at: t + 0.42 + i * 0.07, dur: 0.3, peak: 0.05 * g }));
    return run;
  },
  // 짐 — 한 음씩 처지는 "와-와-와아"(둥글게, 길지 않게).
  'match.lose': (ctx, t, g) => {
    tone(ctx, { f: 392, to: 370, at: t, dur: 0.2, peak: 0.13 * g, type: 'triangle' });
    tone(ctx, { f: 329.63, to: 311, at: t + 0.17, dur: 0.2, peak: 0.13 * g, type: 'triangle' });
    tone(ctx, { f: 261.63, to: 208, at: t + 0.34, dur: 0.36, peak: 0.13 * g, type: 'triangle' });
    return 0.7;
  },
  'match.draw': (ctx, t, g) => plucks(ctx, t, [523.25, 523.25], 0.17, 0.16, 0.12 * g, 0.26),
  // 기권·시간패·나감 — 힘 빠지는 낮은 "뿌웁".
  'match.resign': (ctx, t, g) => {
    tone(ctx, { f: 400, to: 150, at: t, dur: 0.22, peak: 0.19 * g });
    return 0.22;
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
