/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다
// (styles/cssSources.ts 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { GAME_TUTORIALS, tutorialFor } from './gameTutorials';
import { TICHU_TUTORIAL } from '@/features/tichu/tutorial/tichuTutorial';

/**
 * D-121 — 튜토리얼 레지스트리. 클라에서 "튜토리얼용 게임 id" 를 아는 곳은 여기 하나뿐이다.
 * 허브·대기실은 `tutorialFor(gameId)` 만 보고 게임 폴더를 직접 import 하지 않는다.
 */

function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

describe('tutorialFor', () => {
  it('티츄 id 로 티츄 튜토리얼을 돌려준다', () => {
    expect(tutorialFor('TICHU')).toBe(TICHU_TUTORIAL);
  });

  it('대소문자를 정규화한다 (loadGame 과 같은 규약)', () => {
    expect(tutorialFor('tichu')).toBe(TICHU_TUTORIAL);
  });

  it('미등록 id·null·undefined 는 undefined — 버튼이 안 뜰 뿐 깨지지 않는다', () => {
    expect(tutorialFor('COMING_SOON')).toBeUndefined();
    expect(tutorialFor(null)).toBeUndefined();
    expect(tutorialFor(undefined)).toBeUndefined();
    expect(tutorialFor('')).toBeUndefined();
  });

  it('Object 프로토타입 키에 속지 않는다', () => {
    expect(tutorialFor('constructor')).toBeUndefined();
    expect(tutorialFor('toString')).toBeUndefined();
  });
});

describe('GAME_TUTORIALS', () => {
  const all = Object.values(GAME_TUTORIALS);

  it('모든 seenKey 가 고유하다 — 한 게임을 본 기록이 다른 게임 노출을 막으면 안 된다', () => {
    const keys = all.map((t) => t.seenKey);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('티츄는 레거시 키를 그대로 쓴다 — 이미 본 사람에게 다시 띄우지 않는다', () => {
    expect(TICHU_TUTORIAL.seenKey).toBe('mirboard.tutorial.seen.v1');
  });

  it('모든 튜토리얼이 단계와 접근성 설명을 갖는다', () => {
    for (const t of all) {
      expect(t.steps.length).toBeGreaterThan(0);
      expect(t.description.trim()).not.toBe('');
    }
  });
});

describe('게임 중립 페이지는 게임 튜토리얼 폴더를 직접 import 하지 않는다', () => {
  it.each(['../../pages/GameHubPage.tsx', '../../pages/RoomPage.tsx'])('%s', (path) => {
    const source = readSource(path);
    expect(source).not.toMatch(/features\/(tichu|skullking)\/tutorial/);
  });
});
