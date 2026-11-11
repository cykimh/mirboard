import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cloudrouteCssSource, responsiveCssSource } from '@/styles/cssSources';
import { tollOf } from './board';
import { cloudrouteFxBus, type FxItem } from './cloudrouteFxBus';
import { cloudrouteRoomSink } from './cloudrouteRoomSink';
import { useCloudRouteStore } from './cloudrouteStore';
import { snapshotOf, tableOf } from './testTables';
import { CLOUD_ROUTE_THEME } from './theme';
import { BOARD_GAP_U, BOARD_PAD_U, CELL_U, stepPath, tileCenter, tokenPoint } from './tokenLayout';

/** D-218 — 그림책 테마의 화면 전용 연출: 버스·말 자리(배속·따라잡기는 `cloudrouteFxCatchUp.test.tsx`, D-219). 스토어(진짜 값)는 연출과 무관하게 바로 바뀐다. */

describe('연출 버스 — sink 는 스토어에 바로 적용하고, 감독이 붙어 있을 때만 화면 값을 넘긴다', () => {
  const seen: (FxItem | 'clear')[] = [];
  const handler = (item: FxItem | 'clear') => seen.push(item);

  beforeEach(() => {
    seen.length = 0;
    useCloudRouteStore.getState().reset('r-1');
    useCloudRouteStore.getState().applySnapshot(snapshotOf(tableOf()));
  });
  afterEach(() => cloudrouteFxBus.detach(handler));

  const moved = { type: 'TOKEN_MOVED', payload: { seat: 0, from: 0, to: 5, kind: 'ROLL', salary: 0, cash: 1500 } };

  it('감독이 없으면 아무것도 쌓지 않는다 — 스토어만 바뀐다', () => {
    expect(cloudrouteFxBus.attached).toBe(false);
    expect(cloudrouteRoomSink.applyEvent(moved)).toBe('applied');
    expect(useCloudRouteStore.getState().seats[0].position).toBe(5);
    expect(seen).toEqual([]);
  });

  it('감독이 붙으면 적용 전·후 화면 값을 함께 넘긴다 — 스토어는 그 전에 이미 진짜 값이다', () => {
    cloudrouteFxBus.attach(handler);
    cloudrouteRoomSink.applyEvent(moved);
    expect(useCloudRouteStore.getState().seats[0].position).toBe(5);
    expect(seen).toHaveLength(1);
    const item = seen[0] as FxItem;
    expect(item.type).toBe('TOKEN_MOVED');
    expect(item.before.seats[0].position).toBe(0);
    expect(item.after.seats[0].position).toBe(5);
  });

  it('모르는 이벤트는 넘기지 않고, 권위 스냅샷·방 초기화는 남은 연출을 버리게 한다', () => {
    cloudrouteFxBus.attach(handler);
    expect(cloudrouteRoomSink.applyEvent({ type: 'NOPE', payload: {} })).toBe('unhandled');
    expect(seen).toEqual([]);
    cloudrouteRoomSink.applySnapshot(snapshotOf(tableOf()));
    cloudrouteRoomSink.reset('r-2');
    expect(seen).toEqual(['clear', 'clear']);
  });
});

describe('말 자리 — 칸 그림·이름을 가리지 않게 칸 안쪽 가장자리 옆에 선다', () => {
  const HALF = 3.6;

  it('CSS 의 판 여백·칸 사이와 같은 수를 쓴다', () => {
    expect(cloudrouteCssSource).toContain(`gap: calc(var(--cr-u) * ${BOARD_GAP_U});`);
    expect(cloudrouteCssSource).toContain(`padding: calc(var(--cr-u) * ${BOARD_PAD_U});`);
    expect(cloudrouteCssSource).toContain('width: calc(var(--cr-u) * 7.2);');
  });

  it('옆줄 칸의 말은 칸과 0.5u 넘게 겹치지 않는다(24칸 중 모서리 4칸 제외)', () => {
    for (let tile = 0; tile < 24; tile++) {
      if (tile % 6 === 0) continue;
      const c = tileCenter(tile);
      const p = tokenPoint(tile);
      const gapX = Math.abs(p.x - c.x) - CELL_U / 2 - HALF;
      const gapY = Math.abs(p.y - c.y) - CELL_U / 2 - HALF;
      expect(Math.max(gapX, gapY), `tile ${tile}`).toBeGreaterThan(-0.5);
    }
  });

  it('모서리 칸의 말은 안쪽 꼭짓점에 걸친다 — 칸의 1/4 넘게 덮지 않는다', () => {
    for (const tile of [0, 6, 12, 18]) {
      const c = tileCenter(tile);
      const p = tokenPoint(tile);
      const coverX = CELL_U / 2 + HALF - Math.abs(p.x - c.x);
      const coverY = CELL_U / 2 + HALF - Math.abs(p.y - c.y);
      expect(coverX / CELL_U, `tile ${tile}`).toBeLessThan(0.26);
      expect(coverY / CELL_U, `tile ${tile}`).toBeLessThan(0.26);
    }
  });

  it('같은 칸의 말들은 서로 다른 자리에 서고, 모두 판 안에 있다', () => {
    for (let tile = 0; tile < 24; tile++) {
      for (const count of [2, 3, 4]) {
        const points = Array.from({ length: count }, (_, i) => tokenPoint(tile, i, count));
        const keys = new Set(points.map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`));
        expect(keys.size, `tile ${tile} × ${count}`).toBe(count);
        for (const p of points) {
          expect(p.x).toBeGreaterThan(HALF);
          expect(p.x).toBeLessThan(100 - HALF);
          expect(p.y).toBeGreaterThan(HALF);
          expect(p.y).toBeLessThan(100 - HALF);
        }
      }
    }
  });

  it('한 칸씩 가는 길 — 앞으로는 출발 항구를 돌아 넘고, 뒤로도 간다', () => {
    expect(stepPath(22, 1)).toEqual([23, 0, 1]);
    expect(stepPath(2, 23, -1)).toEqual([1, 0, 23]);
    expect(stepPath(5, 5)).toEqual([]);
  });
});

describe('지금 통행료(표시용) — 룰 §6.3 배수와 10G 단위 반올림', () => {
  it('독점 ×1.5 · 축제 ×2 · 둘 다 ×3, 5 는 올림', () => {
    expect(tollOf(1, 3, false, false)).toBe(170);
    expect(tollOf(1, 3, true, false)).toBe(260);
    expect(tollOf(1, 3, false, true)).toBe(340);
    expect(tollOf(1, 3, true, true)).toBe(510);
    expect(tollOf(22, 0, true, false)).toBe(80);
  });
});

describe('테마 층', () => {
  it('기본값은 그림책 테마다 — 되돌리기는 theme.ts 한 줄', () => {
    expect(CLOUD_ROUTE_THEME).toBe('storybook');
  });

  it('그림책 테마 규칙은 모두 루트 클래스 아래에 있고, 움직임 줄이기는 17 에서 끈다', () => {
    expect(cloudrouteCssSource).toContain('.cr-table.cr-storybook {');
    expect(cloudrouteCssSource).toContain('html.dark .cr-table.cr-storybook {');
    const story = cloudrouteCssSource.slice(cloudrouteCssSource.indexOf('.cr-table.cr-storybook {'));
    expect(story).not.toContain('color-mix(');
    const reduced = responsiveCssSource.slice(responsiveCssSource.lastIndexOf('.cr-table *::after'));
    expect(reduced).toContain('animation: none !important;');
  });
});
