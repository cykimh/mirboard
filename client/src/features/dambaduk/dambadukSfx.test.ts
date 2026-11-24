import { beforeEach, describe, expect, it } from 'vitest';
import { useDambadukStore } from './dambadukStore';
import {
  HOME_AFTER_SLIDE_SEC,
  OTHERS_GAIN,
  WALL_AFTER_SLIDE_SEC,
  WALL_DROP_SEC,
  WALL_THUD_SEC,
  dambadukCues,
  dambadukErrorCues,
} from './dambadukSfx';
import { dambadukCssSource } from '@/styles/cssSources';
import { placingView, playingView, snapshot } from './dambadukFixtures';

const run = (ev: { type: string; seq?: number; payload: unknown }, mySeat: number) => {
  const before = useDambadukStore.getState();
  before.applyEvent(ev);
  return dambadukCues(ev, before, useDambadukStore.getState(), mySeat);
};

describe('dambadukCues — 공개 이벤트 → 효과음(순수 함수)', () => {
  beforeEach(() => {
    useDambadukStore.getState().reset('r1');
    useDambadukStore.getState().applySnapshot(snapshot(playingView()));
  });

  it('옮김 = 미끄러짐 + 담이 떨어져 닿는 순간의 "쿵"(D-307), 남의 수는 작게', () => {
    const ev = {
      type: 'STONE_MOVED',
      seq: 2,
      payload: { seat: 1, stoneId: 5, path: ['RIGHT'], fromX: 4, fromY: 3, toX: 5, toY: 3, wall: 'UP', wallFace: 60, jumpedFace: -1, timedOut: false },
    };
    expect(run(ev, 0)).toEqual([
      { cue: 'stone.slide', gain: OTHERS_GAIN },
      { cue: 'wall.build', gain: OTHERS_GAIN, delaySec: WALL_THUD_SEC },
    ]);
    expect(run(ev, 0)).toEqual([]); // 이미 적용한 이벤트는 조용하다
  });

  it('D-307 — 소리 박자는 CSS 연출과 같은 값이다(미끄러짐·담이 닿는 때·집 번짐)', () => {
    expect(dambadukCssSource).toContain(`--db-slide: ${WALL_AFTER_SLIDE_SEC}s;`);
    expect(dambadukCssSource).toContain(`--db-drop: ${WALL_DROP_SEC}s;`);
    expect(WALL_THUD_SEC).toBeCloseTo(WALL_AFTER_SLIDE_SEC + WALL_DROP_SEC, 9);
    // 떨어지는 움직임(0.325s)의 40% 가 닿는 순간이다.
    expect(dambadukCssSource).toContain('animation: db-wall-drop 0.325s linear both;');
    expect(0.325 * 0.4).toBeCloseTo(WALL_DROP_SEC, 9);
    const homeDelay = (HOME_AFTER_SLIDE_SEC - WALL_AFTER_SLIDE_SEC).toFixed(1);
    expect(dambadukCssSource.split(`animation-delay: calc(var(--db-slide) + ${homeDelay}s);`).length - 1).toBe(2);
  });

  it('집이 새로 생기면 터를 싣는 TURN_CHANGED 에서 "퐁" — 내 집은 크게, 남의 집은 작게, 배치 단계는 조용하다', () => {
    const territory = (scores: number[]) => ({ regions: [], scores, largest: scores });
    const turn = (scores: number[], phase = 'PLAYING') => ({
      type: 'TURN_CHANGED',
      seq: 2,
      payload: { seat: 1, phase, territory: territory(scores), placeKinds: [], options: [] },
    });
    expect(run(turn([0, 0]), 0)).toEqual([]);
    expect(run(turn([5, 0]), 0)).toEqual([{ cue: 'home.made', gain: 1, delaySec: HOME_AFTER_SLIDE_SEC }]);
    expect(run(turn([5, 3]), 0)).toEqual([{ cue: 'home.made', gain: OTHERS_GAIN, delaySec: HOME_AFTER_SLIDE_SEC }]);
    expect(run(turn([5, 3]), 0)).toEqual([]);
    useDambadukStore.getState().reset('r1');
    useDambadukStore.getState().applySnapshot(snapshot(placingView()));
    expect(run(turn([49, 0], 'PLACING'), 0)).toEqual([]);
  });

  it('판 끝 — 진 사람은 패배음, 이긴 사람·관전자는 승리음, 무승부는 무승부음', () => {
    const payload = {
      reason: 'SEPARATED',
      standings: [],
      winners: [1],
      draw: false,
      causeSeat: -1,
      territory: { regions: [], scores: [], largest: [] },
      moveCount: 20,
    };
    expect(run({ type: 'MATCH_ENDED', payload }, 0)[0].cue).toBe('match.lose');
    useDambadukStore.getState().reset('r1');
    useDambadukStore.getState().applySnapshot(snapshot(playingView()));
    expect(run({ type: 'MATCH_ENDED', payload: { ...payload, draw: true, winners: [] } }, 0)[0].cue).toBe('match.draw');
  });

  it('거절 코드는 낮은 "툭", 인프라 거절은 조용하다', () => {
    expect(dambadukErrorCues('WALL_IN_PATH')).toEqual([{ cue: 'move.rejected' }]);
    expect(dambadukErrorCues('BUSY')).toEqual([]);
  });
});
