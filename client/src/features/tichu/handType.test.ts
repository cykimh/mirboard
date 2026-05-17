import { describe, expect, it } from 'vitest';
import type { Card } from '@/types/tichu';
import { comboLabel, detectHandType, handTypeLabel } from './handType';

const n = (suit: Card['suit'], rank: number): Card => ({ suit, rank, special: null });
const sp = (special: Card['special']): Card => ({ suit: null, rank: 0, special });

describe('detectHandType', () => {
  it('empty → null', () => {
    expect(detectHandType([])).toBeNull();
  });

  it('single normal / special → SINGLE', () => {
    expect(detectHandType([n('JADE', 5)])).toBe('SINGLE');
    expect(detectHandType([sp('DRAGON')])).toBe('SINGLE');
    expect(detectHandType([sp('PHOENIX')])).toBe('SINGLE');
  });

  it('pair / triple / bomb (동일 rank)', () => {
    expect(detectHandType([n('JADE', 7), n('SWORD', 7)])).toBe('PAIR');
    expect(detectHandType([n('JADE', 7), n('SWORD', 7), n('STAR', 7)])).toBe('TRIPLE');
    expect(
      detectHandType([n('JADE', 7), n('SWORD', 7), n('STAR', 7), n('PAGODA', 7)]),
    ).toBe('BOMB');
  });

  it('full house = 3+2', () => {
    expect(
      detectHandType([
        n('JADE', 7), n('SWORD', 7), n('STAR', 7),
        n('JADE', 4), n('SWORD', 4),
      ]),
    ).toBe('FULL_HOUSE');
  });

  it('5+ 연속 = STRAIGHT, 동일 suit = STRAIGHT_FLUSH_BOMB', () => {
    expect(
      detectHandType([
        n('JADE', 5), n('SWORD', 6), n('STAR', 7), n('PAGODA', 8), n('JADE', 9),
      ]),
    ).toBe('STRAIGHT');
    expect(
      detectHandType([
        n('JADE', 5), n('JADE', 6), n('JADE', 7), n('JADE', 8), n('JADE', 9),
      ]),
    ).toBe('STRAIGHT_FLUSH_BOMB');
  });

  it('Mahjong 은 STRAIGHT 의 rank 1', () => {
    expect(
      detectHandType([
        sp('MAHJONG'), n('JADE', 2), n('SWORD', 3), n('STAR', 4), n('PAGODA', 5),
      ]),
    ).toBe('STRAIGHT');
  });

  it('연속 페어 (6장)', () => {
    expect(
      detectHandType([
        n('JADE', 5), n('SWORD', 5),
        n('STAR', 6), n('PAGODA', 6),
        n('JADE', 7), n('SWORD', 7),
      ]),
    ).toBe('CONSECUTIVE_PAIRS');
  });

  it('연속 페어 (4장, 2페어 — D-73)', () => {
    expect(
      detectHandType([
        n('JADE', 5), n('SWORD', 5),
        n('STAR', 6), n('PAGODA', 6),
      ]),
    ).toBe('CONSECUTIVE_PAIRS');
  });

  it('Dog/Dragon 은 단독 외 조합 불가 → null', () => {
    expect(detectHandType([sp('DOG'), n('JADE', 5)])).toBeNull();
    expect(detectHandType([sp('DRAGON'), n('JADE', 5)])).toBeNull();
  });

  it('Phoenix best-effort: +1장 → PAIR, +동일 rank 2장 → TRIPLE', () => {
    expect(detectHandType([sp('PHOENIX'), n('JADE', 9)])).toBe('PAIR');
    expect(detectHandType([sp('PHOENIX'), n('JADE', 9), n('SWORD', 9)])).toBe('TRIPLE');
  });

  it('Phoenix 복잡 케이스 → null (서버 판정 위임)', () => {
    expect(
      detectHandType([sp('PHOENIX'), n('JADE', 5), n('SWORD', 6), n('STAR', 7)]),
    ).toBeNull();
  });

  it('비합법 조합 → null', () => {
    expect(detectHandType([n('JADE', 5), n('SWORD', 9)])).toBeNull(); // 다른 rank 2장
    expect(
      detectHandType([n('JADE', 5), n('SWORD', 6), n('STAR', 7), n('PAGODA', 8)]),
    ).toBeNull(); // 4연속 (스트레이트 최소 5)
  });
});

describe('handTypeLabel', () => {
  it('한국어 라벨 매핑', () => {
    expect(handTypeLabel('STRAIGHT')).toBe('스트레이트');
    expect(handTypeLabel('BOMB')).toBe('폭탄');
    expect(handTypeLabel(null)).toBe('?');
  });
});

describe('comboLabel (rank 포함)', () => {
  it('페어2 / 트리플K / 풀하우스5', () => {
    expect(comboLabel([n('JADE', 2), n('SWORD', 2)])).toBe('페어2');
    expect(comboLabel([n('JADE', 13), n('SWORD', 13), n('STAR', 13)])).toBe('트리플K');
    expect(
      comboLabel([
        n('JADE', 5), n('SWORD', 5), n('STAR', 5),
        n('JADE', 8), n('SWORD', 8),
      ]),
    ).toBe('풀하우스5');
  });

  it('싱글 — 일반 rank / 특수 이름', () => {
    expect(comboLabel([n('JADE', 14)])).toBe('싱글A');
    expect(comboLabel([sp('DRAGON')])).toBe('싱글드래곤');
  });

  it('스트레이트 — 최고 rank', () => {
    expect(
      comboLabel([
        n('JADE', 5), n('SWORD', 6), n('STAR', 7), n('PAGODA', 8), n('JADE', 9),
      ]),
    ).toBe('스트레이트9');
  });

  it('폭탄7', () => {
    expect(
      comboLabel([n('JADE', 7), n('SWORD', 7), n('STAR', 7), n('PAGODA', 7)]),
    ).toBe('폭탄7');
  });

  it('조합 불명 → ?', () => {
    expect(comboLabel([n('JADE', 5), n('SWORD', 9)])).toBe('?');
  });
});
