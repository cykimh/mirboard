import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ONE_CARD_TUTORIAL_STEPS } from './onecardTutorialSteps';
import { ONE_CARD_TUTORIAL } from './onecardTutorial';

/**
 * D-129 — 튜토리얼 문구 ↔ `docs/rules-onecard.md` 동기화 가드. 튜토리얼은 룰 문서의 요약이라 룰이 바뀌면 조용히
 * 틀린 설명을 한다. 단계마다 근거 §를 `source` 로 박고, 해석이 갈리는 수치·예외 문구를 여기서 고정한다.
 */

const steps = ONE_CARD_TUTORIAL_STEPS;

function bodyText(title: string): string {
  const step = steps.find((s) => s.title === title);
  if (!step) throw new Error(`단계 없음: ${title}`);
  const { container, unmount } = render(<>{step.body}</>);
  const text = container.textContent ?? '';
  unmount();
  return text;
}

describe('원카드 튜토리얼 — 구조', () => {
  it('12단계이고 제목이 고유하다', () => {
    expect(steps).toHaveLength(12);
    expect(new Set(steps.map((s) => s.title)).size).toBe(12);
  });

  it('첫 단계는 환영, 끝의 두 단계는 퀴즈와 반응 연습이다', () => {
    expect(steps[0].title).toBe('미르보드 원카드에 오신 걸 환영합니다');
    expect(steps[10].title).toBe('연습 — 낼 수 있을까?');
    expect(steps[11].title).toBe('연습 — 원카드! 반응');
  });

  it('모든 단계가 룰 문서 §를 근거로 인용한다', () => {
    for (const s of steps) {
      expect(s.source, `${s.title} 에 source 가 없다`).toMatch(/§\d+/);
    }
  });

  it('선언이 단계·전용 열람 키·토큰 클래스를 갖는다', () => {
    expect(ONE_CARD_TUTORIAL.steps).toBe(steps);
    expect(ONE_CARD_TUTORIAL.seenKey).toBe('mirboard.tutorial.one_card.seen.v1');
    // 다이얼로그는 body 포털(.oc-table 밖)이라 칩·연습 버튼 토큰을 여기서 푼다.
    expect(ONE_CARD_TUTORIAL.bodyClassName).toBe('oc-tokens');
  });
});

describe('원카드 튜토리얼 — 룰 문서와 맞물린 문구 (§ 인용)', () => {
  it('인원 2~6명 (§2)', () => {
    expect(bodyText('미르보드 원카드에 오신 걸 환영합니다')).toContain('2~6명');
  });

  it('카드 54장과 공격 값 +2·+3·+5·+7 (§1)', () => {
    const text = bodyText('카드 구성 — 54장');
    expect(text).toContain('54장');
    for (const v of ['2(+2)', 'A(+3)', '흑백 조커(+5)', '컬러 조커(+7)']) expect(text).toContain(v);
  });

  it('7장씩 나누고 시작 카드는 일반 카드 (§3)', () => {
    const text = bodyText('분배와 시작');
    expect(text).toContain('7장');
    expect(text).toContain('일반 카드');
  });

  it('낼 수 있어도 먹을 수 있고, 먹으면 차례가 끝난다 (§4·§7)', () => {
    const text = bodyText('내 차례 — 내기 또는 먹기');
    expect(text).toContain('일부러 먹을 수 있습니다');
    expect(text).toContain('차례가 끝납니다');
  });

  it('7 은 와일드가 아니다 (§5.2)', () => {
    expect(bodyText('낼 수 있는 카드')).toContain('7 은 와일드가 아닙니다');
  });

  it('반격 세기 순서 (§6.2)', () => {
    expect(bodyText('공격과 반격')).toContain('2 < A < 흑백 조커 < 컬러 조커');
  });

  it('2인이면 J·Q 도 한 번 더 (§8.1)', () => {
    expect(bodyText('특수 카드 — J·Q·K·7')).toContain('2명이면 J·Q 도 "한 번 더"');
  });

  it('경쟁 창 3초·벌칙 1장·봇 1.0~2.5초 (§9)', () => {
    const text = bodyText('원카드! 잡기!');
    expect(text).toContain('3초');
    expect(text).toContain('벌칙 1장');
    expect(text).toContain('1.0~2.5초');
  });

  it('파산은 20장 이상, 탈락 뒤 나가기는 탈주가 아니다 (§10)', () => {
    const text = bodyText('파산과 탈락');
    expect(text).toContain('20장 이상');
    expect(text).toContain('이미 탈락한 뒤에 나가는 것은 탈주가 아닙니다');
  });

  it('동순위는 1, 1, 3 (§11.2)', () => {
    expect(bodyText('종료와 순위')).toContain('1, 1, 3');
  });
});
