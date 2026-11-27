/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 테스트에서만 쓰는 Node 모듈(cssSources 와 같은 이유).
import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StompEnvelope } from '@/types/stomp';
import { wordTempErrorLabels, wordTempRoomSink, wordTempVoidLabels } from './wordTempRoomSink';
import { useWordTempStore } from './wordTempStore';
import { mine, seedStore, view } from './wordTempTestFixtures';

const playCues = vi.fn();
vi.mock('@/features/sound/tableSfx', () => ({ playCues: (c: unknown) => playCues(c) }));

const st = () => useWordTempStore.getState();
const env = (type: string, payload: unknown): StompEnvelope<unknown> => ({ eventId: 'e', type, ts: 0, payload });

describe('wordTempRoomSink (D-103)', () => {
  beforeEach(() => {
    playCues.mockClear();
    seedStore({ setterSeat: 0 }, mine({ answer: '바다' }));
  });

  it('메서드마다 호출 시점의 스토어를 읽는다 — 방이 바뀌어도 새 상태에 꽂힌다', () => {
    wordTempRoomSink.reset('r9');
    expect(st().roomId).toBe('r9');
    wordTempRoomSink.applySnapshot({ roomId: 'r9', phase: 'HINT', eventSeq: 1, tableView: view({ hintCount: 3 }), privateHand: null });
    expect(st().hintCount).toBe(3);
  });

  it('스토어에 lastSeq 가 없다 — 순번 판정은 훅의 몫(D-124)', () => {
    expect('lastSeq' in st()).toBe(false);
    const read = readFileSync as unknown as (p: URL, enc: string) => string;
    const here = (rel: string) => new URL(rel, import.meta.url);
    expect(read(here('./wordTempStore.ts'), 'utf-8')).not.toMatch(/lastSeq\s*[:=]/);
    // 훅은 게임 스토어를 모른다(D-103) — sink 가 유일한 경계다
    expect(read(here('../../ws/useStompRoom.ts'), 'utf-8')).not.toMatch(/wordtemp/i);
  });

  it('공개 이벤트 — 적용한 것만 소리를 낸다(스냅샷·버린 프레임은 조용히)', () => {
    const reveal = { type: 'HINT_REVEALED', payload: { attempt: 1, text: '갈매기', temp: 71, floor: 70, floorMet: true } };
    expect(wordTempRoomSink.applyEvent(reveal)).toBe('applied');
    expect(playCues).toHaveBeenLastCalledWith([{ cue: 'temp.reveal', level: 71 }]);
    playCues.mockClear();
    // 같은 차수의 공개가 또 오면 판은 그대로고 소리도 없다
    wordTempRoomSink.applyEvent(reveal);
    expect(playCues).toHaveBeenLastCalledWith([]);
    playCues.mockClear();
    expect(wordTempRoomSink.applyEvent({ type: 'NOPE', payload: {} })).toBe('ignored');
    expect(playCues).not.toHaveBeenCalled();
  });

  it('힌트는 공개 이벤트(D-320) — 모두의 스토어에 쌓이고 온도만큼 높은 한 음(남의 것은 작게)', () => {
    expect(wordTempRoomSink.applyEvent({ type: 'HINT_SCORED', payload: { seat: 0, text: '여름', temp: 58, count: 1 } })).toBe('applied');
    expect(st().hints).toEqual([{ text: '여름', attempt: 1, status: 'SCORED', temp: 58 }]);
    expect(playCues).toHaveBeenLastCalledWith([{ cue: 'temp.scored', level: 58, gain: 1 }]);
    // 추리자의 스토어에도 그대로 쌓인다
    seedStore();
    wordTempRoomSink.applyEvent({ type: 'HINT_SCORED', payload: { seat: 1, text: '갈매기', temp: 71, count: 1 } });
    expect(st().hints).toHaveLength(1);
    expect(playCues).toHaveBeenLastCalledWith([{ cue: 'temp.scored', level: 71, gain: 0.6 }]);
  });

  it('본인 큐로 온 힌트 프레임(옛 서버)은 받지 않는다 — 힌트는 공개 토픽으로만 쌓인다', () => {
    wordTempRoomSink.applyPrivateEvent(env('HINT_SCORED', { seat: 0, text: '갈매기', temp: 71, count: 1 }));
    expect(st().hints).toEqual([]);
    expect(playCues).not.toHaveBeenCalled();
  });

  it('무효가 된 힌트 — 채점 중 줄을 지우고, 낸 사람(출제자)에게만 화면 위 안내로 이유를 말한다', () => {
    wordTempRoomSink.applyEvent({ type: 'HINT_PENDING', payload: { seat: 0, text: '달려' } });
    expect(st().hints).toHaveLength(1);
    const seq = st().errorSeq;
    wordTempRoomSink.applyEvent({ type: 'HINT_VOID', payload: { seat: 0, text: '달려', reason: 'NOT_A_NOUN', count: 0 } });
    expect(st().hints).toEqual([]);
    expect(st().errorMessage).toBe(wordTempVoidLabels.NOT_A_NOUN);
    expect(st().errorSeq).toBe(seq + 1);
    expect(st().errorSticky).toBe(false);
    wordTempRoomSink.applyEvent({ type: 'HINT_PENDING', payload: { seat: 0, text: '짠맛' } });
    wordTempRoomSink.applyEvent({ type: 'HINT_VOID', payload: { seat: 0, text: '짠맛', reason: 'SCORING_FAILED', count: 0 } });
    expect(st().errorMessage).toBe('채점이 늦어졌어요. 다시 내 주세요.');
    // 추리자에게는 줄만 사라지고 안내는 없다
    seedStore();
    wordTempRoomSink.applyEvent({ type: 'HINT_PENDING', payload: { seat: 1, text: '달려' } });
    wordTempRoomSink.applyEvent({ type: 'HINT_VOID', payload: { seat: 1, text: '달려', reason: 'NOT_A_NOUN', count: 0 } });
    expect(st().hints).toEqual([]);
    expect(st().errorMessage).toBeNull();
  });

  it('거절 — 게임 문구로 보이고 보내기 막음이 풀린다', () => {
    st().noteSent({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '수영장' });
    wordTempRoomSink.applyPrivateEvent(env('ERROR', { code: 'HINT_IS_ANSWER', message: 'x' }));
    expect(st().errorMessage).toBe(wordTempErrorLabels.HINT_IS_ANSWER);
    expect(st().sending).toBeNull();
    wordTempRoomSink.applyPrivateEvent(env('ERROR', { code: 'UNKNOWN_WORD', message: 'x' }));
    expect(st().errorMessage).toContain('아직 모르는 낱말');
  });

  it('룰 §5 의 거절 코드에 모두 문구가 있다', () => {
    for (const code of [
      'MATCH_OVER', 'NOT_YOUR_PHASE', 'BAD_CHOICE', 'ALREADY_CHOSEN', 'HINT_FORMAT', 'HINT_IS_ANSWER', 'HINT_INSIDE_ANSWER',
      'HINT_DUPLICATE', 'HINT_LIMIT', 'SCORING_IN_PROGRESS', 'UNKNOWN_WORD', 'NO_HINTS_YET', 'GUESS_FORMAT', 'ALREADY_GUESSED',
    ]) {
      expect(wordTempErrorLabels[code], code).toBeTruthy();
    }
  });

  it('BUSY — 보낸 것이 있으면 문구 없이 다시 보내기 신호, 두 번째 BUSY 는 문구', () => {
    st().noteSent({ '@action': 'SUBMIT_HINT', turnNo: 1, attempt: 1, text: '여름' });
    wordTempRoomSink.applyPrivateEvent(env('ERROR', { code: 'BUSY', message: 'x' }));
    expect(st().retryNonce).toBe(1);
    expect(st().errorMessage).toBeNull();
    expect(st().sending).not.toBeNull();
    wordTempRoomSink.applyPrivateEvent(env('ERROR', { code: 'BUSY', message: 'x' }));
    expect(st().errorMessage).toContain('다른 처리가 진행 중');
    expect(st().sending).toBeNull();
  });

  it('resync 실패는 저절로 닫히지 않는 안내', () => {
    wordTempRoomSink.setError('다시 받지 못했어요');
    expect(st().errorSticky).toBe(true);
  });

  it('모르는 비공개 타입은 조용히 넘긴다', () => {
    expect(() => wordTempRoomSink.applyPrivateEvent(env('SOMETHING', { seat: 0 }))).not.toThrow();
  });
});
