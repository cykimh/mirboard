import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// ── @stomp/stompjs 가짜 ──────────────────────────────────────────────
// activate() 는 연결을 흉내 내지 않는다 — 테스트가 onConnect 를 직접 불러 "연결됨"·"다시 연결됨"을 만든다.
const { clients } = vi.hoisted(() => ({
  clients: [] as Array<{
    cfg: { onConnect: () => void };
    subscriptions: string[];
    handlers: Map<string, (frame: { body: string }) => void>;
    deactivated: boolean;
  }>,
}));

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    cfg: { onConnect: () => void };
    subscriptions: string[] = [];
    handlers = new Map<string, (frame: { body: string }) => void>();
    deactivated = false;
    constructor(cfg: { onConnect: () => void }) {
      this.cfg = cfg;
      clients.push(this);
    }
    activate() {}
    subscribe(dest: string, cb: (frame: { body: string }) => void) {
      this.subscriptions.push(dest);
      this.handlers.set(dest, cb);
      return { unsubscribe: () => {} };
    }
    deactivate() {
      this.deactivated = true;
    }
  },
}));

import { useRoomMeta } from './useRoomMeta';

const META = '/topic/room/r1/meta';

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
}

/**
 * D-134 — 대기실 메타 소켓. 휴대폰이 백그라운드로 가면 소켓이 죽고(D-79) 그동안의 메타(준비 해제·퇴장·방 소멸)를 놓친다.
 * 다시 구독만 해서는 놓친 것을 받을 길이 없으므로, **다시 연결됐을 때**·**화면이 다시 보일 때**·**온라인이 됐을 때** 방 화면에
 * 다시 맞추라고(`onResync`) 알린다. 첫 연결에서는 부르지 않는다 — 방 화면이 막 `join-or-reconnect` 로 방을 받았다.
 */
describe('useRoomMeta — 다시 맞추기 알림 (D-134)', () => {
  beforeEach(() => {
    clients.length = 0;
    setVisibility('visible');
  });

  afterEach(() => {
    setVisibility('visible');
  });

  function mount(onResync = vi.fn()) {
    const onMeta = vi.fn();
    const view = renderHook(() => useRoomMeta('r1', 'tok', onMeta, undefined, onResync));
    return { view, onMeta, onResync, client: clients.at(-1)! };
  }

  it('첫 연결에서는 부르지 않고, 다시 연결되면 구독한 뒤 부른다', () => {
    const { client, onResync } = mount();

    act(() => client.cfg.onConnect());
    expect(client.subscriptions).toEqual([META]);
    expect(onResync).not.toHaveBeenCalled();

    act(() => client.cfg.onConnect());
    expect(client.subscriptions).toEqual([META, META]);
    expect(onResync).toHaveBeenCalledTimes(1);
  });

  it('화면이 다시 보이면 부르고, 숨겨질 때는 부르지 않는다', () => {
    const { client, onResync } = mount();
    act(() => client.cfg.onConnect());

    setVisibility('hidden');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(onResync).not.toHaveBeenCalled();

    setVisibility('visible');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(onResync).toHaveBeenCalledTimes(1);
  });

  it('온라인이 되면 부른다', () => {
    const { client, onResync } = mount();
    act(() => client.cfg.onConnect());

    act(() => {
      window.dispatchEvent(new Event('online'));
    });

    expect(onResync).toHaveBeenCalledTimes(1);
  });

  it('언마운트하면 리스너를 떼고 소켓을 닫는다', () => {
    const { view, client, onResync } = mount();
    act(() => client.cfg.onConnect());

    view.unmount();
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('online'));

    expect(onResync).not.toHaveBeenCalled();
    expect(client.deactivated).toBe(true);
  });

  it('렌더마다 새 콜백을 넘겨도 소켓을 다시 열지 않고 최신 콜백을 부른다', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ cb }) => useRoomMeta('r1', 'tok', vi.fn(), undefined, cb), {
      initialProps: { cb: first },
    });
    const client = clients.at(-1)!;
    act(() => client.cfg.onConnect());

    rerender({ cb: second });
    act(() => {
      window.dispatchEvent(new Event('online'));
    });

    expect(clients).toHaveLength(1);
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('콜백이 없으면(선택) 다시 연결·보이기에도 아무 일 없다', () => {
    renderHook(() => useRoomMeta('r1', 'tok', vi.fn()));
    const client = clients.at(-1)!;

    expect(() => {
      act(() => client.cfg.onConnect());
      act(() => client.cfg.onConnect());
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'));
        window.dispatchEvent(new Event('online'));
      });
    }).not.toThrow();
  });

  it('메타·방 소멸 프레임은 지금처럼 넘긴다', () => {
    const onMeta = vi.fn();
    const onDestroyed = vi.fn();
    renderHook(() => useRoomMeta('r1', 'tok', onMeta, onDestroyed, vi.fn()));
    const client = clients.at(-1)!;
    act(() => client.cfg.onConnect());

    const room = { roomId: 'r1', status: 'WAITING' };
    act(() => client.handlers.get(META)!({ body: JSON.stringify({ type: 'ROOM_META_UPDATED', payload: room }) }));
    act(() => client.handlers.get(META)!({ body: JSON.stringify({ type: 'ROOM_DESTROYED', payload: {} }) }));

    expect(onMeta).toHaveBeenCalledWith(room);
    expect(onDestroyed).toHaveBeenCalledTimes(1);
  });
});
