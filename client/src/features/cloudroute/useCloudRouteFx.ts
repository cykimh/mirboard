import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type {
  AssetsLiquidated,
  Built,
  CloudRouteLetter,
  DiceRolled,
  FestivalHosted,
  LandBought,
  LetterDrawn,
  MoneyChanged,
  MoneyTransferred,
  PlayerEliminated,
  SeatOnly,
  TakenOver,
  TokenMoved,
  TollPaid,
} from '@/types/cloudroute';
import { partnerOf } from './board';
import { cloudrouteFxBus, type FxItem, type FxView } from './cloudrouteFxBus';
import { g } from './describe';
import { burst, clearParticles, coins, pop, ring, type BurstKind, type PopTone, type Px } from './fxParticles';
import { stepPath, tokenPoint } from './tokenLayout';

/**
 * 구름항로 연출 감독 (D-218). 연출 버스에서 받은 이벤트를 순서대로 재생하며 **보여 주는 값**(말 위치·현금·칸 주인·사건 줄)을 한 걸음씩
 * 따라 그린다. 스토어(진짜 값)는 건드리지 않는다 — 큐가 비면 `view` 는 null 이 되고 게임판은 스토어를 그대로 그린다.
 *
 * <h3>조작을 늦추지 않는 약속</h3>
 * <ul>
 *   <li>결정 패널·차례·타이머는 늘 스토어를 본다. 연출은 그 위에 겹쳐 돌 뿐이다.</li>
 *   <li>쌓이면 빨라진다 — 남은 연출 수에 따라 1 → 1.6 → 2.4 → 3.4배, 내 결정이 와 있는데 남의 연출이 남았으면 2.8배 이상.</li>
 *   <li>{@link SKIP_LOAD}개 넘게 쌓이거나, 내가 결정을 보내거나({@link CloudRouteFx.skip}), 탭이 가려지면 남은 연출을 건너뛴다.</li>
 *   <li>권위 스냅샷(resync)이 오면 버리고 진짜 값으로.</li>
 * </ul>
 */

export type TokenMode = 'idle' | 'hop' | 'land' | 'fly' | 'spin' | 'wig' | 'out';

export interface TokenFx {
  /** 감독이 붙잡은 보여 주는 칸(움직이는 중). 없으면 `view` 의 위치. */
  pos?: number;
  mode: TokenMode;
  /** 같은 모드를 다시 틀 때마다 오른다(키프레임 재시작). */
  n: number;
  ms: number;
}

export interface CardFx {
  letter: CloudRouteLetter;
  seat: number;
  phase: 'in' | 'flip' | 'out';
  n: number;
}

export interface FxState {
  view: FxView | null;
  tokens: Record<number, TokenFx>;
  dice: { n: number; ms: number };
  card: CardFx | null;
  route: { d: string; n: number; ms: number } | null;
  busy: boolean;
}

export interface CloudRouteFx extends FxState {
  /** 남은 연출을 모두 건너뛴다 — 내가 결정을 보낼 때 부른다. */
  skip: () => void;
}

const IDLE: FxState = { view: null, tokens: {}, dice: { n: 0, ms: 0 }, card: null, route: null, busy: false };

/** 시간이 드는 연출 — 밀림 정도는 이것만 센다. */
const ANIMATED = new Set([
  'DICE_ROLLED',
  'TOKEN_MOVED',
  'LAND_BOUGHT',
  'BUILT',
  'TOLL_PAID',
  'TAKEN_OVER',
  'LETTER_DRAWN',
  'MONEY_CHANGED',
  'MONEY_TRANSFERRED',
  'WHIRLPOOL_ENTERED',
  'ESCAPE_FAILED',
  'WHIRLPOOL_LEFT',
  'FESTIVAL_HOSTED',
  'ASSETS_LIQUIDATED',
  'PLAYER_ELIMINATED',
]);

export const SKIP_LOAD = 14;

/** 남은 연출 수 → 재생 배속. */
export function speedFor(load: number, hurry: boolean): number {
  const base = load >= 8 ? 3.4 : load >= 5 ? 2.4 : load >= 3 ? 1.6 : 1;
  return hurry ? Math.max(base * 1.3, 2.8) : base;
}

const seatOfItem = (item: FxItem): number => {
  const p = item.payload as { seat?: number; payer?: number; buyer?: number; from?: number } | null;
  if (!p) return -1;
  return p.seat ?? p.payer ?? p.buyer ?? (item.type === 'MONEY_TRANSFERRED' ? (p.from ?? -1) : -1);
};

interface Options {
  enabled: boolean;
  /** 내 결정이 와 있다 — 남의 연출을 서두른다. */
  hurry: boolean;
  mySeat: number;
}

export function useCloudRouteFx(rootRef: RefObject<HTMLElement | null>, options: Options): CloudRouteFx {
  const [state, setState] = useState<FxState>(IDLE);
  const opts = useRef(options);
  opts.current = options;
  const ctl = useRef({ queue: [] as FxItem[], running: false, skip: false, gen: 0, alive: true, wake: null as (() => void) | null });

  const skip = useCallback(() => {
    const c = ctl.current;
    if (!c.running && c.queue.length === 0) return;
    c.skip = true;
    c.wake?.();
  }, []);

  const enabled = options.enabled;
  useEffect(() => {
    if (!enabled) return;
    const c = ctl.current;
    c.alive = true;

    const root = () => rootRef.current;
    const layer = () => root()?.querySelector<HTMLElement>('.cr-fx') ?? null;
    const find = (selector: string) => root()?.querySelector<HTMLElement>(selector) ?? null;
    const centerOf = (el: Element | null): Px | null => {
      const l = layer();
      if (!el || !l) return null;
      const a = el.getBoundingClientRect();
      const b = l.getBoundingClientRect();
      return { x: a.left + a.width / 2 - b.left, y: a.top + a.height / 2 - b.top };
    };
    const scale = () => {
      const board = find('.cr-board');
      return Math.max(0.55, Math.min(1.25, (board?.clientWidth ?? 500) / 620));
    };
    const tileEl = (tile: number) => find(`[data-cr-tile="${tile}"]`);
    const tokenEl = (seat: number) => find(`[data-cr-token="${seat}"]`);
    const seatEl = (seat: number) => find(`[data-cr-seat="${seat}"]`);
    const cashEl = (seat: number) => find(`[data-cr-seat="${seat}"] .cr-seat-cash`) ?? seatEl(seat);

    const quiet = () => c.skip || document.hidden;
    const fx = {
      burst(at: Element | null, kind: BurstKind) {
        const l = layer();
        const p = centerOf(at);
        if (!quiet() && l && p) burst(l, p, kind, scale());
      },
      pop(at: Element | null, text: string, tone: PopTone, lift = 0) {
        const l = layer();
        const p = centerOf(at);
        if (!quiet() && l && p) pop(l, { x: p.x, y: p.y - lift }, text, tone);
      },
      ring(at: Element | null, color: string) {
        const l = layer();
        const p = centerOf(at);
        if (!quiet() && l && p) ring(l, p, (at as HTMLElement).offsetWidth || 60, color);
      },
      coins(from: Element | null, to: Element | null, count: number, ms: number) {
        const l = layer();
        const a = centerOf(from);
        const b = centerOf(to);
        if (quiet() || !l || !a || !b) return;
        const end = coins(l, a, b, count, ms, scale());
        window.setTimeout(() => mark(to as HTMLElement, 'got', 600), end);
      },
    };
    /** React 가 모르는 속성(`data-cr-fx`)으로 키프레임을 튼다 — 다시 그려도 지워지지 않는다. */
    const mark = (el: HTMLElement | null, name: string, ms: number) => {
      if (!el || quiet()) return;
      delete el.dataset.crFx;
      void el.offsetWidth;
      el.dataset.crFx = name;
      window.setTimeout(() => {
        if (el.dataset.crFx === name) delete el.dataset.crFx;
      }, ms);
    };

    const load = () => c.queue.reduce((n, item) => n + (ANIMATED.has(item.type) ? 1 : 0), 0);
    let current: FxItem | null = null;
    const speed = () => {
      const mine = current !== null && seatOfItem(current) === opts.current.mySeat;
      return speedFor(load(), opts.current.hurry && !mine);
    };
    /** 배속을 먹인 길이. */
    const dur = (ms: number) => Math.max(40, Math.round(ms / speed()));
    const sleep = (ms: number) =>
      new Promise<void>((resolve) => {
        if (quiet() || ms <= 0) {
          resolve();
          return;
        }
        const timer = window.setTimeout(() => {
          c.wake = null;
          resolve();
        }, ms);
        c.wake = () => {
          window.clearTimeout(timer);
          c.wake = null;
          resolve();
        };
      });
    const wait = (ms: number) => sleep(dur(ms));
    const frame = () =>
      new Promise<void>((resolve) => {
        if (quiet()) resolve();
        else requestAnimationFrame(() => resolve());
      });

    async function play(item: FxItem, gen: number): Promise<void> {
      const live = () => c.alive && c.gen === gen;
      const set = (patch: Partial<FxState> | ((s: FxState) => Partial<FxState>)) => {
        if (!live()) return;
        setState((s) => ({ ...s, ...(typeof patch === 'function' ? patch(s) : patch) }));
      };
      const token = (seat: number, next: Partial<TokenFx> & { mode: TokenMode }) =>
        set((s) => {
          const prev = s.tokens[seat];
          return { tokens: { ...s.tokens, [seat]: { ms: 0, ...next, n: (prev?.n ?? 0) + 1 } } };
        });
      const release = (seat: number, mode: TokenMode = 'idle') =>
        set((s) => ({ tokens: { ...s.tokens, [seat]: { mode, n: (s.tokens[seat]?.n ?? 0) + 1, ms: 220 } } }));
      const { before, after } = item;
      const cashView = (view: FxView, cash: Record<number, number>): FxView => ({
        ...view,
        seats: view.seats.map((x) => (x.seat in cash ? { ...x, cash: cash[x.seat] } : x)),
      });

      switch (item.type) {
        case 'DICE_ROLLED': {
          const p = item.payload as DiceRolled;
          const ms = dur(620);
          set((s) => ({ view: { ...after, log: before.log }, dice: { n: s.dice.n + 1, ms } }));
          await sleep(ms);
          if (!live()) return;
          const dice = find('.cr-dice');
          fx.burst(dice, 'dust');
          if (p.doubles) {
            fx.burst(dice, 'sparkle');
            fx.pop(dice, '더블!', 'note', 18);
          }
          set({ view: after });
          await wait(200);
          return;
        }
        case 'TOKEN_MOVED': {
          const p = item.payload as TokenMoved;
          if (p.from === p.to) {
            set({ view: after });
            return;
          }
          const fwd = (p.to - p.from + 24) % 24;
          const back = (p.from - p.to + 24) % 24;
          const dir = p.kind === 'ROLL' ? 1 : p.kind === 'LETTER' ? (fwd <= 3 ? 1 : back <= 3 ? -1 : 0) : 0;
          set({ view: before });
          if (dir !== 0) {
            const path = stepPath(p.from, p.to, dir);
            const base = path.length <= 6 ? 190 : path.length <= 9 ? 160 : 140;
            for (const tile of path) {
              if (!live()) return;
              const ms = dur(base);
              token(p.seat, { pos: tile, mode: 'hop', ms });
              await sleep(ms * 0.82);
              mark(tileEl(tile), tile === p.to ? 'land' : 'pass', 600);
              if (tile === 0 && p.salary > 0 && dir === 1) {
                set((s) => ({ view: s.view ? cashView(s.view, { [p.seat]: p.cash }) : s.view }));
                fx.burst(tileEl(0), 'stars');
                fx.pop(cashEl(p.seat), `급여 +${g(p.salary)}`, 'gain');
                mark(seatEl(p.seat), 'got', 600);
              }
              await sleep(ms * 0.18);
            }
          } else {
            // 비행·편지·소용돌이 — 구름 점선 길을 그리며 무지개 꼬리를 끌고 난다.
            const a = tokenPoint(p.from);
            const b = tokenPoint(p.to);
            const mx = 50 + ((a.x + b.x) / 2 - 50) * 0.3;
            const my = 50 + ((a.y + b.y) / 2 - 50) * 0.3 - 5;
            const ms = dur(p.kind === 'WHIRLPOOL' ? 900 : 1150);
            set((s) => ({
              route: { d: `M${a.x.toFixed(2)} ${a.y.toFixed(2)}Q${mx.toFixed(2)} ${my.toFixed(2)} ${b.x.toFixed(2)} ${b.y.toFixed(2)}`, n: (s.route?.n ?? 0) + 1, ms },
            }));
            token(p.seat, { pos: p.from, mode: 'fly', ms });
            await frame();
            const el = tokenEl(p.seat);
            let anim: Animation | null = null;
            if (el && !quiet() && typeof el.animate === 'function') {
              const frames: Keyframe[] = [];
              for (let i = 0; i <= 20; i++) {
                const t = i / 20;
                const u = 1 - t;
                const x = u * u * a.x + 2 * u * t * mx + t * t * b.x;
                const y = u * u * a.y + 2 * u * t * my + t * t * b.y;
                frames.push({ transform: `translate(calc(var(--cr-u) * ${x.toFixed(2)} - 50%), calc(var(--cr-u) * ${y.toFixed(2)} - 50%))` });
              }
              anim = el.animate(frames, { duration: ms, easing: 'cubic-bezier(.45,0,.4,1)', fill: 'forwards' });
            }
            await sleep(ms);
            token(p.seat, { pos: p.to, mode: 'land', ms: 0 });
            await frame();
            anim?.cancel();
            mark(tileEl(p.to), 'land', 600);
            if (p.salary > 0) {
              fx.burst(tileEl(0), 'stars');
              fx.pop(cashEl(p.seat), `급여 +${g(p.salary)}`, 'gain');
            }
          }
          if (!live()) return;
          token(p.seat, { pos: p.to, mode: 'land', ms: 0 });
          fx.burst(tokenEl(p.seat), 'dust');
          await wait(150);
          set({ view: after, route: null });
          release(p.seat, 'land');
          return;
        }
        case 'LAND_BOUGHT': {
          const p = item.payload as LandBought;
          set({ view: after });
          await frame();
          const el = tileEl(p.tile);
          mark(el, p.level > 0 ? 'claim-build' : 'claim', 900);
          fx.burst(el, 'confetti');
          fx.burst(el, 'sparkle');
          fx.pop(cashEl(p.seat), `−${g(p.paid)}`, 'loss');
          const partner = partnerOf(p.tile);
          if (partner >= 0 && after.tiles.find((t) => t.tile === partner)?.owner === p.seat) {
            await wait(380);
            for (const t of [p.tile, partner]) {
              mark(tileEl(t), 'mono', 1100);
              fx.ring(tileEl(t), '#ffd34d');
            }
            fx.burst(el, 'stars');
            fx.pop(el, '섬 독점 ×1.5', 'gain', 14);
            await wait(420);
            return;
          }
          await wait(520);
          return;
        }
        case 'BUILT': {
          const p = item.payload as Built;
          set({ view: after });
          await frame();
          const el = tileEl(p.tile);
          mark(el, 'build', 900);
          fx.burst(el, 'stars');
          fx.burst(el, 'sparkle');
          if (p.paid > 0) fx.pop(cashEl(p.seat), `−${g(p.paid)}`, 'loss');
          else fx.pop(el, '선물!', 'gain', 14);
          await wait(480);
          return;
        }
        case 'TOLL_PAID': {
          const p = item.payload as TollPaid;
          const el = tileEl(p.tile);
          mark(el, 'toll', 800);
          fx.pop(el, `통행료 ${g(p.amount)}`, 'loss', 14);
          await wait(260);
          if (!live()) return;
          set((s) => ({ view: cashView(s.view ?? before, { [p.payer]: p.payerCash }) }));
          fx.pop(cashEl(p.payer), `−${g(p.amount)}`, 'loss');
          const ms = dur(640);
          fx.coins(tokenEl(p.payer) ?? el, cashEl(p.owner), Math.max(4, Math.min(10, Math.round(p.amount / 60) + 3)), ms);
          await sleep(ms + 80);
          set({ view: after });
          fx.pop(cashEl(p.owner), `+${g(p.amount)}`, 'gain');
          await wait(120);
          return;
        }
        case 'TAKEN_OVER': {
          const p = item.payload as TakenOver;
          const el = tileEl(p.tile);
          mark(el, 'toll', 700);
          const ms = dur(650);
          fx.coins(cashEl(p.buyer), cashEl(p.seller), 7, ms);
          fx.pop(cashEl(p.buyer), `−${g(p.price)}`, 'loss');
          await sleep(ms * 0.7);
          set({ view: after });
          await frame();
          mark(el, 'claim', 900);
          fx.burst(el, 'confetti');
          fx.pop(el, '인수!', 'note', 14);
          fx.pop(cashEl(p.seller), `+${g(p.price)}`, 'gain');
          await wait(420);
          return;
        }
        case 'LETTER_DRAWN': {
          const p = item.payload as LetterDrawn;
          set((s) => ({
            view: { ...before, deckCount: after.deckCount },
            card: { letter: p.letter, seat: p.seat, phase: 'in', n: (s.card?.n ?? 0) + 1 },
          }));
          await wait(430);
          if (!live()) return;
          set((s) => ({ card: s.card ? { ...s.card, phase: 'flip' } : null }));
          await wait(300);
          fx.burst(find('.cr-card'), 'sparkle');
          set({ view: after });
          await wait(950);
          set((s) => ({ card: s.card ? { ...s.card, phase: 'out' } : null }));
          await wait(240);
          set({ card: null });
          return;
        }
        case 'MONEY_CHANGED': {
          const p = item.payload as MoneyChanged;
          set({ view: after });
          if (p.delta === 0) return;
          const cash = cashEl(p.seat);
          if (p.delta > 0) {
            const ms = dur(560);
            fx.coins(tokenEl(p.seat), cash, 6, ms);
            fx.pop(cash, `+${g(p.delta)}`, 'gain');
          } else {
            fx.pop(cash, `−${g(-p.delta)}`, 'loss');
            mark(seatEl(p.seat), 'lost', 600);
            fx.burst(tokenEl(p.seat), 'dust');
          }
          await wait(420);
          return;
        }
        case 'MONEY_TRANSFERRED': {
          const p = item.payload as MoneyTransferred;
          const ms = dur(600);
          fx.coins(cashEl(p.from), cashEl(p.to), 5, ms);
          fx.pop(cashEl(p.from), `−${g(p.amount)}`, 'loss');
          set((s) => ({ view: cashView(s.view ?? before, { [p.from]: p.fromCash }) }));
          await sleep(ms * 0.45);
          set({ view: after });
          fx.pop(cashEl(p.to), `+${g(p.amount)}`, 'gain');
          await wait(140);
          return;
        }
        case 'WHIRLPOOL_ENTERED': {
          const p = item.payload as SeatOnly;
          const ms = dur(620);
          token(p.seat, { mode: 'spin', ms });
          mark(tileEl(6), 'toll', 700);
          set({ view: after });
          await sleep(ms);
          return;
        }
        case 'ESCAPE_FAILED': {
          const p = item.payload as SeatOnly;
          const ms = dur(420);
          token(p.seat, { mode: 'wig', ms });
          set({ view: after });
          await sleep(ms);
          return;
        }
        case 'WHIRLPOOL_LEFT': {
          const p = item.payload as SeatOnly;
          set({ view: after });
          token(p.seat, { mode: 'land', ms: 0 });
          fx.burst(tokenEl(p.seat), 'sparkle');
          await wait(280);
          return;
        }
        case 'FESTIVAL_HOSTED': {
          const p = item.payload as FestivalHosted;
          set({ view: after });
          await frame();
          const el = tileEl(p.tile);
          mark(el, 'claim', 900);
          fx.ring(el, '#ff8f6b');
          fx.burst(el, 'stars');
          fx.pop(el, '등불 축제 ×2', 'gain', 14);
          await wait(480);
          return;
        }
        case 'ASSETS_LIQUIDATED': {
          const p = item.payload as AssetsLiquidated;
          for (const sale of p.sales) {
            if (!live()) return;
            mark(tileEl(sale.tile), 'release', 600);
            fx.coins(tileEl(sale.tile), cashEl(p.seat), 3, dur(480));
            await wait(170);
          }
          set({ view: after });
          const got = p.sales.reduce((sum, x) => sum + x.received, 0);
          if (got > 0) fx.pop(cashEl(p.seat), `+${g(got)}`, 'gain');
          await wait(360);
          return;
        }
        case 'PLAYER_ELIMINATED': {
          const p = item.payload as PlayerEliminated;
          const ms = dur(1250);
          set({ view: { ...before, log: after.log } });
          token(p.seat, { mode: 'out', ms });
          mark(seatEl(p.seat), 'lost', 900);
          fx.burst(tokenEl(p.seat), 'rain');
          fx.pop(tokenEl(p.seat), p.reason === 'BANKRUPT' ? '파산…' : '탈주', 'loss', 22);
          await sleep(ms * 0.55);
          for (const tile of p.returnedTiles) {
            if (!live()) return;
            mark(tileEl(tile), 'sink', 900);
            fx.burst(tileEl(tile), 'rain');
            await wait(130);
          }
          await sleep(ms * 0.45);
          set({ view: after });
          return;
        }
        default:
          set({ view: after });
      }
    }

    async function pump() {
      if (c.running) return;
      c.running = true;
      setState((s) => (s.busy ? s : { ...s, busy: true }));
      while (c.alive && c.queue.length > 0) {
        if (load() >= SKIP_LOAD) c.skip = true;
        const item = c.queue.shift()!;
        current = item;
        try {
          await play(item, c.gen);
        } catch {
          // 연출은 장식이다 — 실패하면 그 연출만 버리고 진짜 값으로 이어 간다.
        }
        current = null;
      }
      c.running = false;
      c.skip = false;
      if (c.alive) setState((s) => ({ ...IDLE, dice: s.dice, tokens: {} }));
    }

    const handler = (item: FxItem | 'clear') => {
      if (item === 'clear') {
        c.gen++;
        c.queue.length = 0;
        // 재생 중이던 연출만 서둘러 끝낸다(멈춰 있을 때 켜 두면 다음 연출까지 건너뛴다).
        c.skip = c.running;
        c.wake?.();
        const l = layer();
        if (l) clearParticles(l);
        setState((s) => ({ ...IDLE, dice: s.dice }));
        return;
      }
      c.queue.push(item);
      void pump();
    };
    cloudrouteFxBus.attach(handler);
    const onHide = () => {
      if (document.hidden) c.wake?.();
    };
    document.addEventListener('visibilitychange', onHide);
    return () => {
      c.alive = false;
      c.queue.length = 0;
      c.wake?.();
      cloudrouteFxBus.detach(handler);
      document.removeEventListener('visibilitychange', onHide);
    };
  }, [enabled, rootRef]);

  return { ...state, skip };
}
