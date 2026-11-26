import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeAudioContext, audioLog } from '@/features/sound/testAudio';
import type { TableCue } from '@/features/sound/tableSfx';
import type { MatchResult } from '@/types/wordtemp';
import { attemptDeltas, clockText, normalizeWord, tempBand } from './wordTempMath';
import { BOIL_AFTER_REVEAL_SEC, OTHERS_CORRECT_GAIN, wordTempCues, wordTempPrivateCues } from './wordTempSfx';
import { useWordTempStore, type WordTempRoomState } from './wordTempStore';
import { seedStore } from './wordTempTestFixtures';

const TEMP_CUES: TableCue[] = ['temp.send', 'temp.scored', 'temp.reveal', 'temp.boil', 'temp.correct', 'temp.wrong', 'temp.answer'];

function room(over: Partial<WordTempRoomState> = {}): WordTempRoomState {
  seedStore();
  return { ...useWordTempStore.getState(), ...over };
}

const REVEALED = { attempt: 1, text: '갈매기', temp: 71, floor: 50, floorMet: true };

describe('wordTempMath', () => {
  it('띠 경계 — 96 · 75 · 40(룰 §1)', () => {
    expect([0, 39, 40, 74, 75, 95, 96, 100].map(tempBand)).toEqual(['cold', 'cold', 'warm', 'warm', 'hot', 'hot', 'boil', 'boil']);
  });

  it('보내기 전 다듬기 — NFC 로 합치고 공백을 뺀다', () => {
    const decomposed = '사과'; // "사과"를 풀어 쓴 자모(NFD)
    expect(normalizeWord(decomposed)).toBe('사과');
    expect(normalizeWord(' 사 과　')).toBe('사과');
    expect(normalizeWord('   ')).toBe('');
  });

  it('남은 시간 글자', () => {
    expect(clockText(90_000)).toBe('1:30');
    expect(clockText(59_001)).toBe('1:00');
    expect(clockText(9_400)).toBe('10');
    expect(clockText(0)).toBe('0');
  });

  it('차수의 점수 증감 — 맞힌 사람 +라운드, 출제자는 setterGain', () => {
    const guesses = [
      { seat: 0, text: '바다', pass: false, correct: true, masked: false },
      { seat: 2, text: '항구', pass: false, correct: false, masked: false },
    ];
    expect(attemptDeltas({ attempt: 1, guesses, setterGain: 0 }, 1, 3)).toEqual({ 0: 3 });
    expect(attemptDeltas({ attempt: 2, guesses: [guesses[1]], setterGain: 4 }, 1, 2)).toEqual({ 1: 4 });
  });
});

describe('wordTempCues — 공개 이벤트', () => {
  it('공개 — 온도만큼 높은 음, 96 이상이면 뒤에 끓는 소리', () => {
    const before = room();
    const e = (temp: number) => ({ type: 'HINT_REVEALED', payload: { ...REVEALED, temp } });
    expect(wordTempCues(e(71), before, room({ revealed: [REVEALED] }))).toEqual([{ cue: 'temp.reveal', level: 71 }]);
    expect(wordTempCues(e(97), before, room({ revealed: [{ ...REVEALED, temp: 97 }] }))).toEqual([
      { cue: 'temp.reveal', level: 97 },
      { cue: 'temp.boil', delaySec: BOIL_AFTER_REVEAL_SEC },
    ]);
    // 힌트 0개(text null)·이미 받은 공개는 조용히
    expect(wordTempCues({ type: 'HINT_REVEALED', payload: { ...REVEALED, text: null, temp: -1 } }, before, before)).toEqual([]);
    expect(wordTempCues(e(71), before, before)).toEqual([]);
  });

  it('동시 공개 — 내가 맞히면 또렷하게, 남이 맞히면 작게, 모두 틀리면 식는 소리', () => {
    const before = room();
    const after = room({ attempts: [{ attempt: 1, guesses: [], setterGain: 0 }] });
    const e = (guesses: unknown[]) => ({ type: 'GUESSES_REVEALED', payload: { attempt: 1, guesses, setterSeat: 1, setterGain: 0, scores: [] } });
    const g = (seat: number, correct: boolean) => ({ seat, text: 'x', pass: false, correct, masked: false });
    expect(wordTempCues(e([g(0, true)]), before, after)).toEqual([{ cue: 'temp.correct', gain: 1 }]);
    expect(wordTempCues(e([g(2, true), g(0, false)]), before, after)).toEqual([{ cue: 'temp.correct', gain: OTHERS_CORRECT_GAIN }]);
    expect(wordTempCues(e([g(0, false)]), before, after).map((c) => c.cue)).toEqual(['temp.wrong']);
    expect(wordTempCues(e([g(0, true)]), before, before)).toEqual([]);
  });

  it('차례 끝 — 정답 공개 세 음. 누가 맞혀 끝났으면 조용히', () => {
    const before = room();
    const after = room({ history: [{ turnNo: 1, round: 1, setterSeat: 1, answer: '바다', revealed: [], solvers: [], setterGain: 0, reason: 'THREE_ATTEMPTS' }] });
    const e = (reason: string) => ({ type: 'TURN_ENDED', payload: { turnNo: 1, answer: '바다', hints: [], reason, totals: [] } });
    expect(wordTempCues(e('THREE_ATTEMPTS'), before, after)).toEqual([{ cue: 'temp.answer' }]);
    expect(wordTempCues(e('SOLVED'), before, after)).toEqual([]);
  });

  it('판 끝 — 내 순위로 이김·짐, 모두 비기면 비김, 관전자는 조용히', () => {
    const result: MatchResult = {
      endReason: 'FINISHED',
      draw: false,
      standings: [
        { seat: 0, rank: 1, total: 5, setterPoints: 2, guessPoints: 3, out: false },
        { seat: 1, rank: 2, total: 3, setterPoints: 3, guessPoints: 0, out: false },
      ],
    };
    const e = { type: 'MATCH_ENDED', payload: {} };
    expect(wordTempCues(e, room(), room({ result }))).toEqual([{ cue: 'match.win' }]);
    expect(wordTempCues(e, room({ mySeat: 1 }), room({ mySeat: 1, result }))).toEqual([{ cue: 'match.lose' }]);
    expect(wordTempCues(e, room(), room({ result: { ...result, draw: true } }))).toEqual([{ cue: 'match.draw' }]);
    expect(wordTempCues(e, room({ mySeat: -1 }), room({ mySeat: -1, result }))).toEqual([]);
  });

  it('본인 큐 — 채점된 내 힌트만 소리가 난다', () => {
    expect(wordTempPrivateCues('HINT_SCORED', { seat: 0, text: '여름', temp: 58, count: 1 })).toEqual([{ cue: 'temp.scored', level: 58 }]);
    expect(wordTempPrivateCues('HINT_PENDING', { seat: 0, text: '짠맛' })).toEqual([]);
  });
});

describe('낱말 온도 소리 — D-271', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    audioLog.reset();
    window.localStorage.removeItem('mirboard.sfxMuted');
    vi.stubGlobal('AudioContext', FakeAudioContext);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('둥근 소리(sine·triangle)만, 잡음 없이, 짧고 작게 — 어느 온도에서도', async () => {
    vi.resetModules();
    const sfx = await import('@/features/sound/tableSfx');
    for (const cue of TEMP_CUES) {
      for (const level of [0, 50, 100]) {
        sfx.resetTableSfx();
        expect(sfx.playTableSfx(cue, { level }), cue).toBe(true);
      }
    }
    expect(audioLog.noises).toHaveLength(0);
    for (const t of audioLog.tones) {
      expect(['sine', 'triangle']).toContain(t.type);
      expect(t.stop - 10).toBeLessThan(1.1);
      expect(t.peak).toBeGreaterThan(0);
      expect(t.peak).toBeLessThan(0.3);
    }
  });

  it('온도가 높을수록 높은 음 — 0° 는 낮게, 100° 는 두 옥타브 위', async () => {
    vi.resetModules();
    const sfx = await import('@/features/sound/tableSfx');
    const pitches = [0, 20, 40, 60, 80, 100].map((t) => sfx.tempPitch(t));
    expect([...pitches].sort((a, b) => a - b)).toEqual(pitches);
    expect(new Set(pitches).size).toBe(pitches.length);
    expect(pitches[5] / pitches[0]).toBeCloseTo(4, 5);
    expect(sfx.tempPitch(undefined)).toBe(sfx.tempPitch(50));
    expect(sfx.tempPitch(999)).toBe(sfx.tempPitch(100));
  });
});
