import { describe, expect, it } from 'vitest';
import { cardAssetSrc, type Card } from './tichu';

describe('cardAssetSrc', () => {
  it('maps normal cards to /cards/{suit}-{rank}.webp', () => {
    const c: Card = { suit: 'JADE', rank: 2, special: null };
    expect(cardAssetSrc(c)).toBe('/cards/jade-2.webp');
  });

  it('maps face ranks to J/Q/K/A', () => {
    expect(cardAssetSrc({ suit: 'SWORD', rank: 11, special: null })).toBe('/cards/sword-J.webp');
    expect(cardAssetSrc({ suit: 'STAR', rank: 12, special: null })).toBe('/cards/star-Q.webp');
    expect(cardAssetSrc({ suit: 'PAGODA', rank: 13, special: null })).toBe('/cards/pagoda-K.webp');
    expect(cardAssetSrc({ suit: 'JADE', rank: 14, special: null })).toBe('/cards/jade-A.webp');
  });

  it('maps special cards to /cards/{name}.webp', () => {
    expect(cardAssetSrc({ suit: null, rank: 0, special: 'MAHJONG' })).toBe('/cards/mahjong.webp');
    expect(cardAssetSrc({ suit: null, rank: 0, special: 'DOG' })).toBe('/cards/dog.webp');
    expect(cardAssetSrc({ suit: null, rank: 0, special: 'PHOENIX' })).toBe('/cards/phoenix.webp');
    expect(cardAssetSrc({ suit: null, rank: 0, special: 'DRAGON' })).toBe('/cards/dragon.webp');
  });
});
