import { beforeEach, describe, expect, it } from 'vitest';
import { effectForHandType, useEffectStore } from './effectStore';

describe('effectStore', () => {
  beforeEach(() => {
    useEffectStore.getState().clear();
  });

  it('trigger sets active effect', () => {
    useEffectStore.getState().trigger('BOMB');
    const a = useEffectStore.getState().active;
    expect(a).not.toBeNull();
    expect(a?.kind).toBe('BOMB');
    expect(a?.expiresAt).toBeGreaterThan(Date.now());
  });

  it('successive trigger replaces with new id', () => {
    useEffectStore.getState().trigger('BOMB');
    const first = useEffectStore.getState().active!;
    useEffectStore.getState().trigger('STRAIGHT_FLUSH_BOMB');
    const second = useEffectStore.getState().active!;
    expect(second.id).not.toBe(first.id);
    expect(second.kind).toBe('STRAIGHT_FLUSH_BOMB');
  });

  it('clear sets active to null', () => {
    useEffectStore.getState().trigger('BOMB');
    useEffectStore.getState().clear();
    expect(useEffectStore.getState().active).toBeNull();
  });
});

describe('effectForHandType', () => {
  it('maps BOMB / STRAIGHT_FLUSH_BOMB → effect kind', () => {
    expect(effectForHandType('BOMB')).toBe('BOMB');
    expect(effectForHandType('STRAIGHT_FLUSH_BOMB')).toBe('STRAIGHT_FLUSH_BOMB');
  });

  it('returns null for non-bomb hand types', () => {
    expect(effectForHandType('SINGLE')).toBeNull();
    expect(effectForHandType('PAIR')).toBeNull();
    expect(effectForHandType('TRIPLE')).toBeNull();
    expect(effectForHandType('FULL_HOUSE')).toBeNull();
    expect(effectForHandType('STRAIGHT')).toBeNull();
    expect(effectForHandType('CONSECUTIVE_PAIRS')).toBeNull();
  });
});
