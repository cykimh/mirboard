import { beforeEach, describe, expect, it } from 'vitest';
import { useColorblindStore } from './colorblindStore';

describe('colorblindStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useColorblindStore.setState({ enabled: false });
    document.documentElement.removeAttribute('data-colorblind');
  });

  it('defaults to disabled', () => {
    expect(useColorblindStore.getState().enabled).toBe(false);
  });

  it('toggles, persists, and reflects on <html>', () => {
    useColorblindStore.getState().toggle();
    expect(useColorblindStore.getState().enabled).toBe(true);
    expect(localStorage.getItem('mirboard.colorblind')).toBe('1');
    expect(document.documentElement.getAttribute('data-colorblind')).toBe('1');
  });

  it('init restores enabled from storage', () => {
    localStorage.setItem('mirboard.colorblind', '1');
    useColorblindStore.getState().init();
    expect(useColorblindStore.getState().enabled).toBe(true);
    expect(document.documentElement.getAttribute('data-colorblind')).toBe('1');
  });
});
