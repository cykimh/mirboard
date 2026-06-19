import { describe, expect, it } from 'vitest';
import type { Card, Hand } from '@/types/tichu';
import {
  analyzeHand,
  canBeat,
  comboLabel,
  detectHandType,
  handTypeLabel,
  isSelectionPlayable,
} from './handType';

const n = (suit: Card['suit'], rank: number): Card => ({ suit, rank, special: null });
const sp = (special: Card['special']): Card => ({ suit: null, rank: 0, special });
const hand = (
  type: Hand['type'],
  rank: number,
  length: number,
  cards: Card[] = [],
  phoenixSingle = false,
): Hand => ({ type, rank, length, cards, phoenixSingle });

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

describe('analyzeHand (type/rank/length 분석)', () => {
  it('싱글 — 일반/마작/드래곤 rank 센티넬, 피닉스 플래그', () => {
    expect(analyzeHand([n('JADE', 9)])).toMatchObject({
      type: 'SINGLE',
      rank: 9,
      length: 1,
      phoenixSingle: false,
    });
    expect(analyzeHand([sp('MAHJONG')])).toMatchObject({ rank: 1 });
    expect(analyzeHand([sp('DRAGON')])).toMatchObject({ rank: 100 });
    expect(analyzeHand([sp('PHOENIX')])).toMatchObject({ phoenixSingle: true });
  });

  it('페어/트리플 rank = 자연 카드 rank (피닉스 와일드 포함)', () => {
    expect(analyzeHand([n('JADE', 7), n('SWORD', 7)])).toMatchObject({ type: 'PAIR', rank: 7 });
    expect(analyzeHand([sp('PHOENIX'), n('JADE', 9)])).toMatchObject({ type: 'PAIR', rank: 9 });
    expect(analyzeHand([sp('PHOENIX'), n('JADE', 9), n('SWORD', 9)])).toMatchObject({
      type: 'TRIPLE',
      rank: 9,
    });
  });

  it('인식 불가 → null', () => {
    expect(analyzeHand([n('JADE', 5), n('SWORD', 9)])).toBeNull();
  });
});

describe('canBeat (서버 HandComparator 미러)', () => {
  const pair = (r: number) => analyzeHand([n('JADE', r), n('SWORD', r)])!;

  it('같은 타입·길이 고랭크만 이김', () => {
    expect(canBeat(pair(8), hand('PAIR', 7, 2))).toBe(true);
    expect(canBeat(pair(7), hand('PAIR', 7, 2))).toBe(false);
    expect(canBeat(pair(6), hand('PAIR', 7, 2))).toBe(false);
  });

  it('다른 길이 스트레이트는 못 이김(같은 길이 고랭크만)', () => {
    const s5 = analyzeHand([
      n('JADE', 5), n('SWORD', 6), n('STAR', 7), n('PAGODA', 8), n('JADE', 9),
    ])!;
    expect(canBeat(s5, hand('STRAIGHT', 10, 6))).toBe(false);
    expect(canBeat(s5, hand('STRAIGHT', 8, 5))).toBe(true);
  });

  it('폭탄은 비폭탄 전부 이김, 폭탄끼리는 고랭크, 비폭탄은 폭탄 못 이김', () => {
    const bomb7 = analyzeHand([
      n('JADE', 7), n('SWORD', 7), n('STAR', 7), n('PAGODA', 7),
    ])!;
    expect(canBeat(bomb7, hand('PAIR', 14, 2))).toBe(true);
    expect(canBeat(bomb7, hand('BOMB', 5, 4))).toBe(true);
    expect(canBeat(bomb7, hand('BOMB', 9, 4))).toBe(false);
    expect(canBeat(pair(14), hand('BOMB', 5, 4))).toBe(false);
  });

  it('스트레이트플러시폭탄은 일반 폭탄/모든 것을 이김', () => {
    const sfb = analyzeHand([
      n('JADE', 5), n('JADE', 6), n('JADE', 7), n('JADE', 8), n('JADE', 9),
    ])!;
    expect(canBeat(sfb, hand('BOMB', 14, 4))).toBe(true);
    expect(canBeat(sfb, hand('PAIR', 3, 2))).toBe(true);
  });

  it('피닉스 싱글: 드래곤 빼고 모든 싱글 이김, 폭탄엔 짐', () => {
    const ph = analyzeHand([sp('PHOENIX')])!;
    expect(canBeat(ph, hand('SINGLE', 9, 1, [n('JADE', 9)]))).toBe(true);
    expect(canBeat(ph, hand('SINGLE', 100, 1, [sp('DRAGON')]))).toBe(false);
    expect(canBeat(ph, hand('BOMB', 5, 4))).toBe(false);
  });
});

describe('isSelectionPlayable (내기 버튼 UI 게이트)', () => {
  it('리드(currentTop 없음) → 인식된 조합 모두 가능(개 단독 포함)', () => {
    expect(isSelectionPlayable([n('JADE', 3), n('SWORD', 3)], null)).toBe(true);
    expect(isSelectionPlayable([sp('DOG')], null)).toBe(true);
  });

  it('follow: 못 이기면 false, 이기면 true', () => {
    const top = hand('PAIR', 7, 2);
    expect(isSelectionPlayable([n('JADE', 6), n('SWORD', 6)], top)).toBe(false);
    expect(isSelectionPlayable([n('JADE', 8), n('SWORD', 8)], top)).toBe(true);
  });

  it('currentTop 이 피닉스 싱글: 싱글/폭탄은 허용(서버 위임), 그 외 타입은 불가', () => {
    const top = hand('SINGLE', 10, 1, [sp('PHOENIX')], true);
    expect(isSelectionPlayable([n('JADE', 5)], top)).toBe(true); // 싱글 → 서버 판정
    expect(
      isSelectionPlayable([n('JADE', 13), n('SWORD', 13), n('STAR', 13), n('PAGODA', 13)], top),
    ).toBe(true); // 폭탄 → 싱글을 이김
    expect(isSelectionPlayable([n('JADE', 6), n('SWORD', 6)], top)).toBe(false); // 페어는 싱글 못 이김
  });

  it('개 단독은 follow 불가', () => {
    expect(isSelectionPlayable([sp('DOG')], hand('SINGLE', 5, 1, [n('JADE', 5)]))).toBe(false);
  });

  it('인식 못 한 조합 → false(어차피 selectedCombo "?" 로 비활성)', () => {
    expect(isSelectionPlayable([n('JADE', 5), n('SWORD', 9)], null)).toBe(false);
  });
});
