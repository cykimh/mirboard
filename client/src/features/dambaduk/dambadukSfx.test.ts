import { beforeEach, describe, expect, it } from 'vitest';
import { useDambadukStore } from './dambadukStore';
import { OTHERS_GAIN, WALL_AFTER_SLIDE_SEC, dambadukCues, dambadukErrorCues } from './dambadukSfx';
import { playingView, snapshot } from './dambadukFixtures';

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

  it('옮김 = 미끄러짐 + 돌이 닿은 뒤 담 쌓기, 남의 수는 작게', () => {
    const ev = {
      type: 'STONE_MOVED',
      seq: 2,
      payload: { seat: 1, stoneId: 5, path: ['RIGHT'], fromX: 4, fromY: 3, toX: 5, toY: 3, wall: 'UP', wallFace: 60, jumpedFace: -1, timedOut: false },
    };
    expect(run(ev, 0)).toEqual([
      { cue: 'stone.slide', gain: OTHERS_GAIN },
      { cue: 'wall.build', gain: OTHERS_GAIN, delaySec: WALL_AFTER_SLIDE_SEC },
    ]);
    expect(run(ev, 0)).toEqual([]); // 이미 적용한 이벤트는 조용하다
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
