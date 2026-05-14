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

describe('tichuStore — Phase 5e sort + reorder', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-sort');
  });

  it('sortedHand returns server order when sortOrder empty', () => {
    loadHand([c7, c2, c5]);
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([cardKey(c7), cardKey(c2), cardKey(c5)]);
  });

  it('sortHandByRank arranges Mahjong < normals < Dragon < Phoenix < Dog', () => {
    loadHand([c7, dragon, c2, phoenix, mahjong, c14, dog, c5]);
    useTichuStore.getState().sortHandByRank();
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

  it('reorderHand splices fromKey before toKey', () => {
    loadHand([c2, c5, c7, c14]);
    useTichuStore.getState().sortHandByRank();
    // 정렬 결과: c2, c5, c7, c14. c14 를 c5 자리 앞으로 이동.
    useTichuStore.getState().reorderHand(cardKey(c14), cardKey(c5));
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([
      cardKey(c2),
      cardKey(c14),
      cardKey(c5),
      cardKey(c7),
    ]);
  });

  it('restoreServerOrder clears sortOrder', () => {
    loadHand([c7, c2, c5]);
    useTichuStore.getState().sortHandByRank();
    useTichuStore.getState().restoreServerOrder();
    const result = sortedHand(useTichuStore.getState());
    expect(result.map(cardKey)).toEqual([cardKey(c7), cardKey(c2), cardKey(c5)]);
  });

  it('sortedHand keeps known order then appends new cards after hand swap', () => {
    loadHand([c2, c5, c7]);
    useTichuStore.getState().sortHandByRank();
    // 사용자가 c7 을 맨 앞으로 옮긴 상태로 가정.
    useTichuStore.getState().reorderHand(cardKey(c7), cardKey(c2));
    // 패스 스왑으로 c2 가 c14 로 교체된 상황을 시뮬레이션.
    useTichuStore.setState({
      privateHand: { seat: 0, cards: [c7, c14, c5] },
    });
    const result = sortedHand(useTichuStore.getState());
    // c7 은 sortOrder 의 첫 번째 위치 유지, c5 는 다음 위치, c14 는 서버 순서 따라 뒤로.
    expect(result.map(cardKey)).toEqual([cardKey(c7), cardKey(c5), cardKey(c14)]);
  });

  it('reorderHand is no-op when from equals to', () => {
    loadHand([c2, c5]);
    useTichuStore.getState().reorderHand(cardKey(c2), cardKey(c2));
    expect(useTichuStore.getState().sortOrder).toEqual([]);
  });

  it('sortHandByRank on empty hand keeps order empty', () => {
    useTichuStore.setState({ privateHand: { seat: 0, cards: [] }, sortOrder: ['stale'] });
    useTichuStore.getState().sortHandByRank();
    expect(useTichuStore.getState().sortOrder).toEqual([]);
  });
});
