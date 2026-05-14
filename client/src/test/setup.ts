import '@testing-library/jest-dom/vitest';

// Node 26 의 내장 localStorage 는 --localstorage-file 옵션 없이는 동작하지 않는 stub
// 으로 노출된다 (ExperimentalWarning). vitest 의 jsdom 환경보다 우선해서 가려질 수 있어
// 안전한 in-memory polyfill 을 강제로 덮어쓴다.
class MemoryStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.has(key) ? this.store.get(key)! : null;
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  setItem(key: string, value: string) {
    this.store.set(key, String(value));
  }
}

Object.defineProperty(globalThis, 'localStorage', {
  value: new MemoryStorage(),
  configurable: true,
  writable: true,
});
Object.defineProperty(globalThis, 'sessionStorage', {
  value: new MemoryStorage(),
  configurable: true,
  writable: true,
});
