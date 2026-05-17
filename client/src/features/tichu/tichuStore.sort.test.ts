import { beforeEach, describe, expect, it } from 'vitest';
import { sortedHand, useTichuStore } from './tichuStore';
import type { Card, PrivateHand } from '@/types/tichu';
import { cardKey } from '@/types/tichu';

const c2: Card = { suit: 'JADE', rank: 2, special: null };
const c5: Card = { suit: 'SWORD', rank: 5, special: null };
const c7: Card = { suit: 'PAGODA', rank: 7, special: null };
const c14: Card = { suit: 'STAR', rank: 14, special: null };
const mahjong: Card = { suit: null, rank: 1, special: 'MAHJONG' };
const dragon: Card = { suit: null, rank: 15, special: 'DRAGON' };
const phoenix: Card = { suit: null, rank: -1, special: 'PHOENIX' };
const dog: Card = { suit: null, rank: 0, special: 'DOG' };

function loadHand(cards: Card[]) {
  const hand: PrivateHand = { seat: 0, cards };
  useTichuStore.setState({ privateHand: hand, sortOrder: [] });
}

describe('tichuStore — Phase 13A 기본 랭크정렬 + reorder', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-sort');
  });

  it('#5: sortOrder 비어있으면 기본 랭크순 (버튼 없이 자동)', () => {
    loadHand([c7, c2, c5]);
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([cardKey(c2), cardKey(c5), cardKey(c7)]);
  });

  it('#5: 특수카드 포함 랭크순 — Mahjong < 일반 < Dragon < Phoenix < Dog', () => {
    loadHand([c7, dragon, c2, phoenix, mahjong, c14, dog, c5]);
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([
      cardKey(mahjong),
      cardKey(c2),
      cardKey(c5),
      cardKey(c7),
      cardKey(c14),
      cardKey(dragon),
      cardKey(phoenix),
      cardKey(dog),
    ]);
  });

  it('reorderHand: 기본 랭크순 기준으로 fromKey 를 toKey 앞으로', () => {
    loadHand([c14, c2, c7, c5]); // 기본 정렬: c2,c5,c7,c14
    useTichuStore.getState().reorderHand(cardKey(c14), cardKey(c5));
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([
      cardKey(c2),
      cardKey(c14),
      cardKey(c5),
      cardKey(c7),
    ]);
  });

  it('수동 재배열은 유지, 새 카드는 뒤에 append', () => {
    loadHand([c2, c5, c7]); // 정렬: c2,c5,c7
    useTichuStore.getState().reorderHand(cardKey(c7), cardKey(c2)); // c7 맨 앞
    useTichuStore.setState({
      privateHand: { seat: 0, cards: [c7, c14, c5] },
    });
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([cardKey(c7), cardKey(c5), cardKey(c14)]);
  });

  it('reorderHand from==to 면 no-op (sortOrder 그대로 []) ', () => {
    loadHand([c2, c5]);
    useTichuStore.getState().reorderHand(cardKey(c2), cardKey(c2));
    expect(useTichuStore.getState().sortOrder).toEqual([]);
  });

  it('새 라운드(reset) 시 sortOrder 리셋 → 다시 기본 랭크순', () => {
    loadHand([c7, c2]);
    useTichuStore.getState().reorderHand(cardKey(c7), cardKey(c2)); // 수동 c7,c2
    expect(useTichuStore.getState().sortOrder.length).toBeGreaterThan(0);
    useTichuStore.getState().reset('room-sort-2');
    expect(useTichuStore.getState().sortOrder).toEqual([]);
  });
});
