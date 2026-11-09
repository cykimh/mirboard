/**
 * 테스트 전용 — jsdom 에는 `PointerEvent` 가 없어 `fireEvent.pointerDown(el, { clientX })` 가 좌표 없는 `Event` 로 만들어진다.
 * testing-library 는 `window.PointerEvent` 가 있으면 그것으로 만들므로 `MouseEvent` 를 넓혀 채운다.
 */
if (typeof window !== 'undefined' && !('PointerEvent' in window)) {
  class TestPointerEvent extends MouseEvent {
    pointerId: number;
    pointerType: string;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 0;
      this.pointerType = init.pointerType ?? 'mouse';
    }
  }
  (window as unknown as { PointerEvent: typeof TestPointerEvent }).PointerEvent = TestPointerEvent;
}
export {};
