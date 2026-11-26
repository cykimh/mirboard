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

  it('여섯 단계 — 온도·차례·힌트·최저 온도·점수·약속', () => {
    expect(WORD_TEMP_TUTORIAL_STEPS).toHaveLength(6);
    const t = all();
    for (const phrase of [
      '온도 0~100°', '40~74°', '75~95°', '96° 이상', '모두가 한 번씩 출제자', '3차', '5개까지', '나에게만', '온도가 가장 높은 하나',
      '채점 중…', '차수별 최저 온도', '1차 — 최저 50°', '2차 — 최저 60°', '3차 — 최저 70°', '답 하나', '한꺼번에 공개', '+1점',
      '1차 +1 · 2차 +2 · 3차 +3점', '라운드 번호만큼 곱해져요', '공동 순위', '최하위',
    ]) {
      expect(t, phrase).toContain(phrase);
    }
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
