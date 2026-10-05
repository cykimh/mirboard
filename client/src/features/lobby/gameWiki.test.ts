import { describe, expect, it } from 'vitest';
import { gameWikiUrl } from './gameWiki';

/** 게임 id → 외부 규칙 문서. 허브의 "자세히" 링크가 쓴다(D-74). 대소문자는 소문자로 정규화한다. */
describe('gameWikiUrl', () => {
  it('원카드는 나무위키 원카드 문서다', () => {
    expect(gameWikiUrl('ONE_CARD')).toBe('https://namu.wiki/w/%EC%9B%90%EC%B9%B4%EB%93%9C');
    expect(decodeURIComponent(gameWikiUrl('one_card')!)).toBe('https://namu.wiki/w/원카드');
  });

  it('등록된 다른 게임도 그대로다', () => {
    expect(gameWikiUrl('TICHU')).toBe('https://en.wikipedia.org/wiki/Tichu');
    expect(gameWikiUrl('SKULL_KING')).toContain('boardgamegeek.com');
  });

  it('없는 게임은 undefined — "자세히" 링크를 그리지 않는다', () => {
    expect(gameWikiUrl('YACHT')).toBeUndefined();
  });
});
