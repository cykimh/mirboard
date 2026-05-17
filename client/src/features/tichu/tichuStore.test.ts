import { beforeEach, describe, expect, it } from 'vitest';
import { useTichuStore } from './tichuStore';
import type { Card } from '@/types/tichu';

const cardA: Card = { suit: 'JADE', rank: 7, special: null };
const cardB: Card = { suit: 'SWORD', rank: 12, special: null };
const cardC: Card = { suit: null, rank: 1, special: 'MAHJONG' };

describe('tichuStore pass selection — Phase 13B(#1) 카드 먼저 → 슬롯', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-1');
  });

  it('카드 선택 후 슬롯 배정', () => {
    const s = useTichuStore.getState();
    s.selectPassCard(cardA);
    expect(useTichuStore.getState().pendingPassCardKey).toBe('N-JADE-7');
    useTichuStore.getState().assignPassSlot('left');
    expect(useTichuStore.getState().passSelection.left).toBe('N-JADE-7');
    expect(useTichuStore.getState().pendingPassCardKey).toBeNull();

    useTichuStore.getState().selectPassCard(cardB);
    useTichuStore.getState().assignPassSlot('partner');
    expect(useTichuStore.getState().passSelection.partner).toBe('N-SWORD-12');

    useTichuStore.getState().selectPassCard(cardC);
    useTichuStore.getState().assignPassSlot('right');
    expect(useTichuStore.getState().passSelection.right).toBe('S-MAHJONG');
  });

  it('같은 카드 재선택 → pending 해제', () => {
    useTichuStore.getState().selectPassCard(cardA);
    useTichuStore.getState().selectPassCard(cardA);
    expect(useTichuStore.getState().pendingPassCardKey).toBeNull();
  });

  it('이미 배정된 카드를 다른 슬롯에 재배정하면 이전 슬롯 비워짐', () => {
    useTichuStore.getState().selectPassCard(cardA);
    useTichuStore.getState().assignPassSlot('left');
    // 배정된 카드 다시 클릭 → 슬롯에서 빠지고 pending 으로
    useTichuStore.getState().selectPassCard(cardA);
    expect(useTichuStore.getState().passSelection.left).toBeNull();
    expect(useTichuStore.getState().pendingPassCardKey).toBe('N-JADE-7');
    useTichuStore.getState().assignPassSlot('right');
    const sel = useTichuStore.getState().passSelection;
    expect(sel.right).toBe('N-JADE-7');
    expect(sel.left).toBeNull();
  });

  it('pending 없이 채워진 슬롯 클릭 → 해제 (되돌리기)', () => {
    useTichuStore.getState().selectPassCard(cardA);
    useTichuStore.getState().assignPassSlot('left');
    useTichuStore.getState().assignPassSlot('left'); // pending 없음 → 해제
    expect(useTichuStore.getState().passSelection.left).toBeNull();
  });

  it('clearPassSelection 이 슬롯 + pending 리셋', () => {
    useTichuStore.getState().selectPassCard(cardA);
    useTichuStore.getState().assignPassSlot('left');
    useTichuStore.getState().selectPassCard(cardB);
    useTichuStore.getState().clearPassSelection();
    const sel = useTichuStore.getState().passSelection;
    expect(sel.left).toBeNull();
    expect(sel.partner).toBeNull();
    expect(sel.right).toBeNull();
    expect(useTichuStore.getState().pendingPassCardKey).toBeNull();
  });
});
