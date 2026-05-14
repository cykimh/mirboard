import { beforeEach, describe, expect, it } from 'vitest';
import { useTichuStore } from './tichuStore';
import type { Card } from '@/types/tichu';

const cardA: Card = { suit: 'JADE', rank: 7, special: null };
const cardB: Card = { suit: 'SWORD', rank: 12, special: null };
const cardC: Card = { suit: null, rank: 1, special: 'MAHJONG' };

describe('tichuStore pass selection', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-1');
  });

  it('assigns cards into slots and advances active slot', () => {
    const store = useTichuStore.getState();
    store.assignPassSlot(cardA);
    expect(useTichuStore.getState().passSelection.left).toBe('N-JADE-7');
    expect(useTichuStore.getState().activePassSlot).toBe('partner');

    useTichuStore.getState().assignPassSlot(cardB);
    expect(useTichuStore.getState().passSelection.partner).toBe('N-SWORD-12');
    expect(useTichuStore.getState().activePassSlot).toBe('right');

    useTichuStore.getState().assignPassSlot(cardC);
    expect(useTichuStore.getState().passSelection.right).toBe('S-MAHJONG');
  });

  it('moving the same card to a different slot vacates the previous slot', () => {
    useTichuStore.getState().assignPassSlot(cardA);
    useTichuStore.getState().assignPassSlot(cardB);
    useTichuStore.getState().setActivePassSlot('right');
    useTichuStore.getState().assignPassSlot(cardA);

    const sel = useTichuStore.getState().passSelection;
    expect(sel.right).toBe('N-JADE-7');
    expect(sel.left).toBeNull();
  });

  it('clearPassSelection resets slots', () => {
    useTichuStore.getState().assignPassSlot(cardA);
    useTichuStore.getState().clearPassSelection();
    const sel = useTichuStore.getState().passSelection;
    expect(sel.left).toBeNull();
    expect(sel.partner).toBeNull();
    expect(sel.right).toBeNull();
    expect(useTichuStore.getState().activePassSlot).toBe('left');
  });
});
