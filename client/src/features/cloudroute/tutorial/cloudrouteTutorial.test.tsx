import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CLOUD_ROUTE_TUTORIAL, CLOUD_ROUTE_TUTORIAL_STEPS } from './cloudrouteTutorial';

/** 튜토리얼 문장이 룰 정본(`docs/rules-cloudroute.md`)의 핵심 수치와 어긋나지 않게 붙잡는다 — 룰을 바꾸면 여기가 먼저 빨개진다. */
describe('구름항로 튜토리얼', () => {
  const text = CLOUD_ROUTE_TUTORIAL_STEPS.map((step) => {
    const { container } = render(<>{step.body}</>);
    return `${step.title} ${container.textContent ?? ''}`;
  }).join(' ');

  it.each([
    ['시작 현금', '1,500G'],
    ['급여', '200G'],
    ['4인 라운드 상한', '20'],
    ['독점 배수', '×1.5'],
    ['축제 배수', '×2'],
    ['세관', '100G'],
    ['풍등탑 인수 불가', '풍등탑'],
  ])('%s', (_, phrase) => {
    expect(text).toContain(phrase);
  });

  it('단계마다 근거 §가 있고 열람 키가 게임 전용이다', () => {
    expect(CLOUD_ROUTE_TUTORIAL_STEPS.every((s) => s.source?.startsWith('§'))).toBe(true);
    expect(CLOUD_ROUTE_TUTORIAL.seenKey).toContain('cloud_route');
  });
});
