import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SKULL_KING_TUTORIAL_STEPS } from './skullkingTutorialSteps';
import { SKULL_KING_TUTORIAL } from './skullkingTutorial';

/**
 * D-121 — 튜토리얼 문구 ↔ `docs/rules-skullking.md` 동기화 가드.
 *
 * <p>튜토리얼은 룰 문서의 요약이라 문서 해석(§13)이 바뀌면 조용히 틀린 설명을 한다. 그래서
 * 단계마다 근거 §를 `source` 로 박고, 해석이 갈리는 수치·예외 문구를 여기서 고정한다 —
 * 룰을 바꾸면 이 테스트가 먼저 빨개져야 한다.
 */

const steps = SKULL_KING_TUTORIAL_STEPS;

/** 제목으로 단계를 찾아 본문 텍스트를 돌려준다. */
function bodyText(title: string): string {
  const step = steps.find((s) => s.title === title);
  if (!step) throw new Error(`단계 없음: ${title}`);
  const { container, unmount } = render(<>{step.body}</>);
  const text = container.textContent ?? '';
  unmount();
  return text;
}

describe('스컬킹 튜토리얼 — 구조', () => {
  it('13단계이고 제목이 고유하다', () => {
    expect(steps).toHaveLength(13);
    expect(new Set(steps.map((s) => s.title)).size).toBe(13);
  });

  it('첫 단계는 환영, 마지막은 준비 완료', () => {
    expect(steps[0].title).toBe('미르보드 스컬킹에 오신 걸 환영합니다');
    expect(steps[steps.length - 1].title).toBe('준비 완료!');
  });

  it('모든 단계가 룰 문서 §를 근거로 인용한다', () => {
    for (const s of steps) {
      expect(s.source, `${s.title} 에 source 가 없다`).toMatch(/§\d+/);
    }
  });

  it('선언이 단계·전용 열람 키·토큰 클래스를 갖는다', () => {
    expect(SKULL_KING_TUTORIAL.steps).toBe(steps);
    expect(SKULL_KING_TUTORIAL.seenKey).toBe('mirboard.tutorial.skull_king.seen.v1');
    // 다이얼로그는 body 포털(.sk-table 밖)이라 칩 색 토큰을 여기서 푼다.
    expect(SKULL_KING_TUTORIAL.bodyClassName).toBe('sk-tokens');
  });
});

describe('스컬킹 튜토리얼 — 룰 문서와 맞물린 문구 (§ 인용)', () => {
  it('카드 구성: 70장, 특수 카드 장수 (§1)', () => {
    const text = bodyText('카드 구성 — 70장');
    expect(text).toContain('70장');
    for (const count of ['해적 5', '인어 2', '스컬킹 1', '티그리스 1', '탈출 5']) {
      expect(text).toContain(count);
    }
  });

  it('8인 9·10라운드는 8장씩 (§4)', () => {
    expect(bodyText('라운드와 손패')).toContain('8장씩');
  });

  it('예측 범위는 0 ~ 손패 장수 (§5, §13-⑪)', () => {
    expect(bodyText('승수 예측')).toContain('0 ~ 손패 장수');
  });

  it('따라내기: 그 색이 손에 없으면 자유, 특수 카드는 언제든 (§6.2)', () => {
    const text = bodyText('트릭 — 같은 색 따라내기');
    expect(text).toContain('손에 없으면');
    expect(text).toContain('특수 카드는 언제든');
  });

  it('탈출 리드는 리드 수트를 보류한다 (§6.1, §13-⑤)', () => {
    const text = bodyText('리드 수트는 언제 정해지나');
    expect(text).toContain('보류');
    expect(text).toContain('리드 수트가 없고');
  });

  it('검정은 오프수트여도 이긴다 (§7.1, §13-⑦)', () => {
    expect(bodyText('검정은 으뜸패')).toContain('검정 1을 낸 사람이 초록 14를 이깁니다');
  });

  it('3자 순환과 예외 (§7)', () => {
    expect(bodyText('해적·인어·스컬킹 — 물고 물리는 셋')).toContain('셋이 모두 나오면 인어');
  });

  it('점수식 (§10)', () => {
    const text = bodyText('점수 — 예측을 맞혔나');
    expect(text).toContain('×20');
    expect(text).toContain('×10');
    expect(text).toContain('라운드 번호');
    // 예시 수치 — §10 표 4경우를 그대로 계산한 값.
    for (const n of ['+60', '−20', '+70', '−70']) expect(text).toContain(n);
  });

  it('보너스: 금액과 3자 트릭 +40 뿐 (§11, §13-⑨)', () => {
    const text = bodyText('보너스 점수');
    for (const n of ['+10', '+20', '+30', '+40']) expect(text).toContain(n);
    expect(text).toContain('+40 뿐');
    expect(text).toContain('14 보너스는 별도');
    expect(text).toContain('맞힌 사람만');
  });
});
