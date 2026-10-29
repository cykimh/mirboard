import { beforeEach, describe, expect, it } from 'vitest';
import { useThemeStore } from './themeStore';

describe('themeStore — 기본은 라이트 (D-146)', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
  });

  it('저장값이 없으면 라이트로 시작한다', () => {
    useThemeStore.getState().init();
    expect(useThemeStore.getState().theme).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('다크를 골라 둔 사람은 다크 그대로', () => {
    localStorage.setItem('mirboard.theme', 'dark');
    useThemeStore.getState().init();
    expect(useThemeStore.getState().theme).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('토글하면 반대 테마를 저장한다', () => {
    useThemeStore.getState().init();
    useThemeStore.getState().toggle();
    expect(localStorage.getItem('mirboard.theme')).toBe('dark');
  });
});
