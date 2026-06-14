import { beforeEach, describe, expect, it } from 'vitest';
import { useReactionStore, REACTION_TTL_MS } from './reactionStore';

describe('reactionStore', () => {
  beforeEach(() => useReactionStore.getState().reset());

  it('add appends a reaction', () => {
    useReactionStore.getState().add(2, '🔥');
    const r = useReactionStore.getState().recent;
    expect(r).toHaveLength(1);
    expect(r[0].fromSeat).toBe(2);
    expect(r[0].emoji).toBe('🔥');
  });

  it('prune removes entries older than TTL', () => {
    useReactionStore.getState().add(1, '👍');
    const ts = useReactionStore.getState().recent[0].ts;
    useReactionStore.getState().prune(ts + REACTION_TTL_MS + 1);
    expect(useReactionStore.getState().recent).toHaveLength(0);
  });

  it('prune keeps fresh entries', () => {
    useReactionStore.getState().add(0, '😂');
    const ts = useReactionStore.getState().recent[0].ts;
    useReactionStore.getState().prune(ts + 100);
    expect(useReactionStore.getState().recent).toHaveLength(1);
  });

  it('reset clears all', () => {
    useReactionStore.getState().add(0, '🎉');
    useReactionStore.getState().reset();
    expect(useReactionStore.getState().recent).toHaveLength(0);
  });
});
