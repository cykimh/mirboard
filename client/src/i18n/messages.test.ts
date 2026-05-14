import { describe, expect, it } from 'vitest';
import { t } from './messages';

describe('i18n messages — Phase 5e', () => {
  it('returns the Korean string for a known key', () => {
    expect(t('hand.title')).toBe('내 손패');
    expect(t('match.ended.titleSuffix')).toBe('승리');
  });

  it('returns the key itself for an unknown key (debug visibility)', () => {
    // @ts-expect-error 의도된 미정의 키 — 런타임 fallback 검증.
    expect(t('totally.bogus.key')).toBe('totally.bogus.key');
  });
});
