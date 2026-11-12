import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeAudioContext, audioLog } from './testAudio';
import type { TableCue } from './tableSfx';

/** D-267 — 게임판 일반 동작 효과음. 가짜 AudioContext 로 무엇을 몇 개 울렸는지만 센다. */
const MUTE_KEY = 'mirboard.sfxMuted';

const ALL_CUES: TableCue[] = [
  'card.play',
  'card.draw',
  'card.attack',
  'card.suit',
  'card.reverse',
  'card.skip',
  'card.again',
  'deck.shuffle',
  'race.open',
  'race.called',
  'race.caught',
  'stone.place',
  'move.rejected',
  'seat.out',
  'match.win',
  'match.lose',
  'match.draw',
  'match.resign',
];

async function fresh() {
  // AudioContext 는 모듈 싱글톤이라 테스트마다 모듈을 새로 읽는다.
  vi.resetModules();
  return import('./tableSfx');
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
  audioLog.reset();
  window.localStorage.removeItem(MUTE_KEY);
  vi.stubGlobal('AudioContext', FakeAudioContext);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.removeItem(MUTE_KEY);
});

describe('tableSfx — 소리', () => {
  it.each(ALL_CUES)('%s 는 짧고 작은 합성음이다', async (cue) => {
    const sfx = await fresh();
    expect(sfx.playTableSfx(cue)).toBe(true);
    expect(audioLog.voices).toBeGreaterThan(0);
    // 길어야 1초 남짓, 크기는 작게(강하게 두기 "쿵" 0.55 의 절반 아래).
    for (const t of audioLog.tones) {
      expect(t.stop - 10).toBeLessThan(1.1);
      expect(t.peak).toBeGreaterThan(0);
      expect(t.peak).toBeLessThan(0.25);
    }
    expect(audioLog.contexts).toBe(1);
  });

  it('여러 장 먹기는 장수만큼 반복하되 4번까지만', async () => {
    const sfx = await fresh();
    const count = (n: number) => {
      audioLog.reset();
      sfx.resetTableSfx();
      sfx.playTableSfx('card.draw', { count: n });
      return audioLog.noises.length;
    };
    expect([count(1), count(2), count(3), count(7)]).toEqual([1, 2, 3, sfx.DRAW_REPEAT_MAX]);
  });

  it('공격은 누적 세기가 오를수록 음이 붙고, 보통 내기보다 크다', async () => {
    const sfx = await fresh();
    const measure = (level: number) => {
      audioLog.reset();
      sfx.resetTableSfx();
      sfx.playTableSfx('card.attack', { level });
      return audioLog.tones.length;
    };
    expect([measure(1), measure(2), measure(3)]).toEqual([1, 2, 3]);
    const attackPeak = Math.max(...audioLog.tones.map((t) => t.peak));
    audioLog.reset();
    sfx.resetTableSfx();
    sfx.playTableSfx('card.play');
    expect(attackPeak).toBeGreaterThan(Math.max(...audioLog.tones.map((t) => t.peak)));
  });

  it('gain 으로 남의 동작을 작게, delaySec 로 늦게 울린다', async () => {
    const sfx = await fresh();
    sfx.playTableSfx('stone.place');
    const mine = Math.max(...audioLog.tones.map((t) => t.peak));
    audioLog.reset();
    sfx.resetTableSfx();
    sfx.playTableSfx('stone.place', { gain: 0.6, delaySec: 0.5 });
    expect(Math.max(...audioLog.tones.map((t) => t.peak))).toBeCloseTo(mine * 0.6, 5);
    expect(Math.min(...audioLog.tones.map((t) => t.start))).toBeCloseTo(10.5, 5);
  });

  it('돌 소리는 잡음이 섞인 "딱", 거절은 잡음 없는 낮은 "툭"', async () => {
    const sfx = await fresh();
    sfx.playTableSfx('move.rejected');
    expect(audioLog.tones).toHaveLength(1);
    expect(audioLog.tones[0].freq).toBeLessThan(200);
    expect(audioLog.noises).toHaveLength(0);
    audioLog.reset();
    sfx.playTableSfx('stone.place');
    expect(audioLog.noises).toHaveLength(1);
  });
});

describe('tableSfx — 재생 규칙', () => {
  it('음소거면 울리지 않는다(오디오 컨텍스트도 만들지 않는다). 다시 켜면 울린다', async () => {
    window.localStorage.setItem(MUTE_KEY, '1');
    const sfx = await fresh();
    for (const cue of ALL_CUES) expect(sfx.playTableSfx(cue)).toBe(false);
    expect(audioLog.voices).toBe(0);
    expect(audioLog.contexts).toBe(0);
    window.localStorage.setItem(MUTE_KEY, '0');
    expect(sfx.playTableSfx('card.play')).toBe(true);
  });

  it('같은 소리는 최소 간격 안에 다시 울리지 않는다(봇이 빠르게 둘 때)', async () => {
    const sfx = await fresh();
    expect(sfx.playTableSfx('card.play')).toBe(true);
    const once = audioLog.voices;
    vi.advanceTimersByTime(sfx.TABLE_SFX_MIN_GAP_MS - 1);
    expect(sfx.playTableSfx('card.play')).toBe(false);
    expect(audioLog.voices).toBe(once);
    vi.advanceTimersByTime(1);
    expect(sfx.playTableSfx('card.play')).toBe(true);
    expect(audioLog.voices).toBe(once * 2);
  });

  it('동시에 울리는 소리는 상한까지만 — 끝나면 다시 울린다. 판 종료음은 막지 않는다', async () => {
    const sfx = await fresh();
    // 긴 소리 셋(각 0.2초 이상)을 한꺼번에.
    expect(sfx.playTableSfx('deck.shuffle')).toBe(true);
    expect(sfx.playTableSfx('seat.out')).toBe(true);
    expect(sfx.playTableSfx('card.reverse')).toBe(true);
    expect(sfx.TABLE_SFX_MAX_VOICES).toBe(3);
    expect(sfx.playTableSfx('card.play')).toBe(false);
    expect(sfx.playTableSfx('match.win')).toBe(true);
    vi.advanceTimersByTime(1000);
    expect(sfx.playTableSfx('card.play')).toBe(true);
  });

  it('Web Audio 가 없는 환경에서는 조용히 넘어간다', async () => {
    vi.stubGlobal('AudioContext', undefined);
    const sfx = await fresh();
    expect(sfx.playTableSfx('card.play')).toBe(false);
    expect(() => sfx.playCues([{ cue: 'match.win' }, { cue: 'stone.place' }])).not.toThrow();
  });

  it('playCues 는 이벤트 하나의 소리들을 순서대로 울린다', async () => {
    const sfx = await fresh();
    sfx.playCues([{ cue: 'match.resign' }, { cue: 'match.lose', delaySec: 0.25 }]);
    const starts = audioLog.tones.map((t) => t.start);
    expect(Math.min(...starts)).toBeCloseTo(10, 5);
    expect(starts.some((s) => Math.abs(s - 10.25) < 1e-6)).toBe(true);
  });
});
