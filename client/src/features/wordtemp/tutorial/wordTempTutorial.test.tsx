import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { GAME_TUTORIALS, tutorialFor } from '@/features/tutorial/gameTutorials';
import { BOIL_FROM, HOT_FROM, WARM_FROM } from '../wordTempMath';
import { WORD_TEMP_TUTORIAL } from './wordTempTutorial';
import { WORD_TEMP_TUTORIAL_STEPS } from './wordTempTutorialSteps';

/** 룰을 바꾸면 여기가 먼저 빨개진다 — 핵심 문구를 붙잡는다(`docs/rules-wordtemp.md`). */
describe('낱말 온도 튜토리얼', () => {
  const text = (i: number) => {
    const { container, unmount } = render(<>{WORD_TEMP_TUTORIAL_STEPS[i].body}</>);
    const t = container.textContent ?? '';
    unmount();
    return t;
  };
  const all = () => WORD_TEMP_TUTORIAL_STEPS.map((_, i) => text(i)).join('\n');

  it('레지스트리에 등록돼 있다(D-121) — 고유한 열람 키, 판 토큰 클래스', () => {
    expect(tutorialFor('WORD_TEMP')).toBe(WORD_TEMP_TUTORIAL);
    expect(tutorialFor('word_temp')).toBe(WORD_TEMP_TUTORIAL);
    expect(WORD_TEMP_TUTORIAL.seenKey).toBe('mirboard.tutorial.wordtemp.seen.v1');
    expect(WORD_TEMP_TUTORIAL.bodyClassName?.split(' ')).toContain('wt-tokens');
    const keys = Object.values(GAME_TUTORIALS).map((t) => t.seenKey);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('여섯 단계 — 온도·차례·힌트·기준 온도·점수·약속(D-320)', () => {
    expect(WORD_TEMP_TUTORIAL_STEPS).toHaveLength(6);
    const t = all();
    for (const phrase of [
      '온도 0~100°', '40~74°', '75~95°', '96° 이상', '모두가 한 번씩 출제자', '5차', '10개까지', '낼 때마다 모두에게',
      '그 자리에서 추리', '채점 중…', '1차 70° · 2차 75° · 3차 80° · 4차 85° · 5차 90°', '기준을 못 넘기면', '이번 차수 점수는 없어요',
      '답 하나', '한꺼번에 공개', '+1점', '차수 번호만큼', '5차 +5점', '라운드 번호만큼 곱해져요', '공동 순위', '최하위',
    ]) {
      expect(t, phrase).toContain(phrase);
    }
    // 예전 규칙(나만 보는 온도·가장 높은 하나만 공개)의 문구가 남아 있지 않다
    for (const gone of ['나에게만', '온도가 가장 높은 하나', '최저 온도']) expect(t).not.toContain(gone);
  });

  it('사회적 약속 한 줄 — 채팅으로 정답을 알려 주지 않기(룰 §12)', () => {
    expect(text(5)).toContain('채팅으로 정답을 알려 주지 않기');
  });

  it('튜토리얼의 띠 경계가 화면이 쓰는 값과 같다(최저 온도는 서버가 준다 — 문구는 룰 §3.4)', () => {
    expect([WARM_FROM, HOT_FROM, BOIL_FROM]).toEqual([40, 75, 96]);
  });

  it('단계마다 룰 문서 근거(§)가 있다', () => {
    for (const s of WORD_TEMP_TUTORIAL_STEPS) expect(s.source).toMatch(/§/);
  });
});
