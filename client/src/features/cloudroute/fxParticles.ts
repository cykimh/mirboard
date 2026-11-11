/**
 * 구름항로 연출 입자 (D-218, D-219 솜구름 꼬리·반짝임·꽃가루 비) — 꽃가루·별·동전·빗방울·먼지·말풍선. 전부 효과 레이어(`.cr-fx`) 안의 잠깐 사는 DOM 조각이고
 * `transform`·`opacity` 만 움직인다(Web Animations). 동시에 사는 조각은 {@link MAX_LIVE} 개까지 — 넘치면 새 조각을 만들지 않는다
 * (휴대폰 60fps). 좌표는 효과 레이어 기준 px.
 */

export interface Px {
  x: number;
  y: number;
}

export const MAX_LIVE = 110;
let live = 0;

const PALETTE = ['#ff9aa2', '#ffd59e', '#b5ead7', '#bcd4ff', '#d9b8ff', '#fff3a6'];

const STAR =
  '<svg viewBox="0 0 18 18"><path d="M9 2l2 4.600 5 .500-3.800 3.300 1.100 4.900L9 12.800l-4.300 2.500 1.100-4.900L2 7.100l5-.500Z" fill="#ffd34d" stroke="#e0a21a" stroke-width=".8" stroke-linejoin="round"/></svg>';
const COIN =
  '<svg viewBox="0 0 18 18"><circle cx="9" cy="9" r="8" fill="#ffd34d" stroke="#e0a21a" stroke-width="1.4"/><path d="M9 4.500l1.300 2.900 3.100.300-2.300 2.100.700 3.100L9 11.300l-2.800 1.600.700-3.100-2.300-2.100 3.100-.300Z" fill="#fff6c9"/></svg>';
const DROP = '<svg viewBox="0 0 18 18"><path d="M9 2q5 7 5 10a5 5 0 0 1-10 0q0-3 5-10Z" fill="#9db7d6"/></svg>';
const PUFF = '<svg viewBox="0 0 18 18"><circle cx="9" cy="9" r="7" fill="#fff" opacity=".85"/></svg>';
const SPARK =
  '<svg viewBox="0 0 18 18"><path d="M9 1q.800 6.200 8 8-7.200 1.800-8 8-.800-6.200-8-8 7.200-1.800 8-8Z" fill="#fff8d6" stroke="#ffd34d" stroke-width=".6"/></svg>';

/** 솜구름 한 덩이 — 이동 꼬리·주사위 착지·바람 빠지는 열기구. */
const CLOUDLET =
  '<svg viewBox="0 0 18 18"><path d="M4.500 13.500a3 3 0 0 1-.400-5.970 4.200 4.200 0 0 1 8.100-.900 3.500 3.500 0 0 1 1.300 6.870Z" fill="#fff" stroke="#b9a8d6" stroke-opacity=".45" stroke-width=".7" stroke-linejoin="round"/></svg>';

function confetto(i: number): string {
  const c = PALETTE[i % PALETTE.length];
  if (i % 3 === 0) return `<svg viewBox="0 0 18 18"><circle cx="9" cy="9" r="5" fill="${c}"/></svg>`;
  if (i % 3 === 1) return `<svg viewBox="0 0 18 18"><path d="M3 14L9 3l6 11Z" fill="${c}"/></svg>`;
  return `<svg viewBox="0 0 18 18"><rect x="3" y="6" width="12" height="5" rx="2.500" fill="${c}"/></svg>`;
}

function spawn(
  layer: HTMLElement,
  html: string,
  className: string,
  frames: Keyframe[],
  options: KeyframeAnimationOptions,
): void {
  if (live >= MAX_LIVE || typeof layer.animate !== 'function') return;
  const el = document.createElement('span');
  el.className = className;
  el.innerHTML = html;
  layer.append(el);
  live++;
  const done = () => {
    if (!el.isConnected) return;
    el.remove();
    live--;
  };
  const anim = el.animate(frames, { fill: 'both', ...options });
  anim.onfinish = done;
  anim.oncancel = done;
}

const rand = (a: number, b: number) => a + Math.random() * (b - a);

export type BurstKind = 'confetti' | 'stars' | 'sparkle' | 'rain' | 'dust' | 'cheer';

const BURSTS: Record<BurstKind, { count: number; reach: [number, number]; rise: number; fall: number; ms: [number, number] }> = {
  confetti: { count: 16, reach: [26, 78], rise: 16, fall: 46, ms: [800, 1300] },
  stars: { count: 7, reach: [22, 60], rise: 26, fall: 10, ms: [700, 1100] },
  sparkle: { count: 8, reach: [14, 46], rise: 8, fall: 0, ms: [500, 900] },
  rain: { count: 10, reach: [10, 40], rise: -6, fall: 70, ms: [700, 1100] },
  dust: { count: 7, reach: [14, 34], rise: 4, fall: -6, ms: [380, 620] },
  cheer: { count: 26, reach: [50, 170], rise: 60, fall: 150, ms: [1300, 2100] },
};

/** 한 점에서 퍼지는 입자 무리. `scale` 은 판 크기에 맞춘 배율(휴대폰은 작게). */
export function burst(layer: HTMLElement, p: Px, kind: BurstKind, scale = 1): void {
  const spec = BURSTS[kind];
  for (let i = 0; i < spec.count; i++) {
    const html =
      kind === 'stars'
        ? STAR
        : kind === 'sparkle'
          ? SPARK
          : kind === 'rain'
            ? DROP
            : kind === 'dust'
              ? PUFF
              : kind === 'cheer' && i % 6 === 5
                ? STAR
                : confetto(i);
    // 먼지는 바닥을 따라 옆으로, 비는 아래로, 나머지는 사방으로.
    const angle =
      kind === 'dust' ? (i % 2 === 0 ? rand(-0.5, 0.3) : rand(Math.PI - 0.3, Math.PI + 0.5)) : kind === 'cheer' ? rand(-Math.PI * 0.95, -Math.PI * 0.05) : rand(0, Math.PI * 2);
    const reach = rand(spec.reach[0], spec.reach[1]) * scale;
    const dx = Math.cos(angle) * reach;
    const dy = Math.sin(angle) * reach;
    const spin = kind === 'rain' || kind === 'dust' ? 0 : rand(-400, 400);
    const rise = spec.rise * scale;
    const fall = spec.fall * scale;
    const size = kind === 'dust' ? rand(0.7, 1.3) : kind === 'sparkle' ? rand(0.6, 1.1) : rand(0.75, 1.1);
    spawn(
      layer,
      html,
      'cr-particle',
      [
        { transform: `translate(${p.x}px,${p.y}px) scale(${0.2 * size})`, opacity: kind === 'dust' ? 0.9 : 1 },
        {
          transform: `translate(${p.x + dx}px,${p.y + dy - rise * 0.6}px) scale(${size}) rotate(${spin}deg)`,
          opacity: kind === 'dust' ? 0.55 : 1,
          offset: 0.5,
        },
        {
          transform: `translate(${p.x + dx * 1.25}px,${p.y + dy * 1.25 - rise * 1.4 + fall}px) scale(${kind === 'dust' ? size * 1.5 : size * 0.5}) rotate(${spin * 1.6}deg)`,
          opacity: 0,
        },
      ],
      { duration: rand(spec.ms[0], spec.ms[1]), easing: 'cubic-bezier(.2,.7,.3,1)', delay: kind === 'cheer' ? rand(0, 160) : 0 },
    );
  }
}

/**
 * 솜구름 꼬리 (D-219) — 말이 떠난 자리에 구름 몇 덩이가 남아 부풀다 흩어진다. `drift` 는 흩어지는 쪽(px, 진행 반대 방향).
 */
export function puffs(layer: HTMLElement, p: Px, count: number, scale = 1, drift: Px = { x: 0, y: 0 }): void {
  for (let i = 0; i < count; i++) {
    const ox = rand(-7, 7) * scale;
    const oy = rand(-3, 5) * scale;
    const size = rand(0.75, 1.25) * scale;
    spawn(
      layer,
      CLOUDLET,
      'cr-particle cr-particle-puff',
      [
        { transform: `translate(${p.x + ox}px,${p.y + oy}px) scale(${0.35 * size})`, opacity: 0 },
        { transform: `translate(${p.x + ox + drift.x * 0.3}px,${p.y + oy + drift.y * 0.3 - 2 * scale}px) scale(${1.1 * size})`, opacity: 0.92, offset: 0.25 },
        {
          transform: `translate(${p.x + ox + drift.x}px,${p.y + oy + drift.y - 9 * scale}px) scale(${1.7 * size})`,
          opacity: 0,
        },
      ],
      { duration: rand(620, 900), delay: i * 45, easing: 'cubic-bezier(.2,.6,.4,1)' },
    );
  }
}

/** 제자리 반짝임 (D-219) — 칸 둘레에서 별빛이 하나씩 켜졌다 꺼진다(구매·건설). `size` 는 칸 한 변(px). */
export function twinkle(layer: HTMLElement, p: Px, size: number, count = 6): void {
  for (let i = 0; i < count; i++) {
    const angle = (i / count) * Math.PI * 2 + rand(-0.4, 0.4);
    const reach = size * rand(0.32, 0.62);
    const x = p.x + Math.cos(angle) * reach;
    const y = p.y + Math.sin(angle) * reach;
    const k = rand(0.7, 1.25);
    spawn(
      layer,
      SPARK,
      'cr-particle',
      [
        { transform: `translate(${x}px,${y}px) scale(0) rotate(0deg)`, opacity: 0 },
        { transform: `translate(${x}px,${y}px) scale(${k}) rotate(60deg)`, opacity: 1, offset: 0.45 },
        { transform: `translate(${x}px,${y}px) scale(0) rotate(120deg)`, opacity: 0 },
      ],
      { duration: rand(420, 640), delay: i * 70, easing: 'ease-in-out' },
    );
  }
}

/** 종이 꽃가루 비 (D-219) — 결과 화면 위에서 한들한들 떨어진다. `x0`~`x1` 사이에서 `height` 만큼. */
export function confettiFall(layer: HTMLElement, x0: number, x1: number, y: number, height: number, count: number): void {
  for (let i = 0; i < count; i++) {
    const x = rand(x0, x1);
    const sway = rand(14, 38) * (i % 2 === 0 ? 1 : -1);
    const spin = rand(-540, 540);
    const size = rand(0.7, 1.2);
    spawn(
      layer,
      i % 7 === 6 ? STAR : confetto(i),
      'cr-particle',
      [
        { transform: `translate(${x}px,${y}px) scale(${size}) rotate(0deg)`, opacity: 0 },
        { transform: `translate(${x + sway}px,${y + height * 0.3}px) scale(${size}) rotate(${spin * 0.3}deg)`, opacity: 1, offset: 0.12 },
        { transform: `translate(${x - sway * 0.6}px,${y + height * 0.65}px) scale(${size}) rotate(${spin * 0.65}deg)`, opacity: 1, offset: 0.6 },
        { transform: `translate(${x + sway * 0.4}px,${y + height}px) scale(${size * 0.8}) rotate(${spin}deg)`, opacity: 0 },
      ],
      { duration: rand(1700, 2700), delay: rand(0, 900), easing: 'linear' },
    );
  }
}

/** 금액 → 날아가는 동전 수 (D-219). 50G 마다 하나, 적어도 2개·많아야 {@link MAX_COINS}개. */
export const MAX_COINS = 12;

export function coinCount(amount: number): number {
  if (!(amount > 0)) return 0;
  return Math.max(2, Math.min(MAX_COINS, Math.round(amount / 50) + 1));
}

/** 별 동전 무리가 포물선으로 날아가 받는 쪽에 쏙 들어간다. 끝나는 시각(ms)을 돌려준다. */
export function coins(layer: HTMLElement, from: Px, to: Px, count: number, ms: number, scale = 1): number {
  const gap = Math.min(70, ms / (count + 2));
  for (let i = 0; i < count; i++) {
    const midX = (from.x + to.x) / 2 + rand(-36, 36) * scale;
    const midY = Math.min(from.y, to.y) - rand(28, 84) * scale;
    const startX = from.x + rand(-8, 8) * scale;
    const startY = from.y + rand(-6, 6) * scale;
    spawn(
      layer,
      COIN,
      'cr-particle cr-particle-coin',
      [
        { transform: `translate(${startX}px,${startY}px) scale(.3)`, opacity: 0 },
        { transform: `translate(${startX}px,${startY - 10 * scale}px) scale(1)`, opacity: 1, offset: 0.14 },
        { transform: `translate(${midX}px,${midY}px) scale(1.2) rotate(200deg)`, opacity: 1, offset: 0.56 },
        { transform: `translate(${to.x}px,${to.y}px) scale(.45) rotate(420deg)`, opacity: 0.85 },
      ],
      { duration: ms, delay: i * gap, easing: 'cubic-bezier(.45,.05,.55,.95)' },
    );
  }
  return ms + (count - 1) * gap;
}

/** 번지는 고리 — 도착·독점·축제. */
export function ring(layer: HTMLElement, p: Px, size: number, color: string): void {
  spawn(
    layer,
    `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="17" fill="none" stroke="${color}" stroke-width="3"/></svg>`,
    'cr-particle cr-particle-ring',
    [
      { transform: `translate(${p.x}px,${p.y}px) scale(${size * 0.012})`, opacity: 0.95 },
      { transform: `translate(${p.x}px,${p.y}px) scale(${size * 0.05})`, opacity: 0 },
    ],
    { duration: 620, easing: 'cubic-bezier(.2,.7,.3,1)' },
  );
}

export type PopTone = 'gain' | 'loss' | 'note';

/** 숫자·한마디 말풍선이 떠올랐다 사라진다. */
export function pop(layer: HTMLElement, p: Px, text: string, tone: PopTone, bouncy = false): void {
  if (live >= MAX_LIVE || typeof layer.animate !== 'function') return;
  const el = document.createElement('span');
  el.className = `cr-pop cr-pop-${tone}`;
  el.textContent = text;
  layer.append(el);
  live++;
  const width = layer.clientWidth;
  const half = el.offsetWidth / 2;
  const x = Math.max(half + 4, Math.min(width - half - 4, p.x));
  const y = Math.max(22, p.y);
  const done = () => {
    if (!el.isConnected) return;
    el.remove();
    live--;
  };
  // D-219 — 튀어 오르는 숫자: 크게 솟았다 한 번 통 튀고 떠오르며 사라진다(잃은 돈은 아래로 떨어진다).
  const end = tone === 'loss' ? y + 14 : y - 40;
  const bounce: Keyframe[] = [
        { transform: `translate(${x}px,${y + 10}px) translate(-50%,-50%) scale(.3)`, opacity: 0 },
        { transform: `translate(${x}px,${y - 24}px) translate(-50%,-50%) scale(1.42)`, opacity: 1, offset: 0.14 },
        { transform: `translate(${x}px,${y - 8}px) translate(-50%,-50%) scale(.94, 1.04)`, opacity: 1, offset: 0.27 },
        { transform: `translate(${x}px,${y - 17}px) translate(-50%,-50%) scale(1.1)`, opacity: 1, offset: 0.38 },
        { transform: `translate(${x}px,${y - 12}px) translate(-50%,-50%) scale(1)`, opacity: 1, offset: 0.48 },
        { transform: `translate(${x}px,${y - 14}px) translate(-50%,-50%) scale(1)`, opacity: 1, offset: 0.76 },
        { transform: `translate(${x}px,${end}px) translate(-50%,-50%) scale(.92)`, opacity: 0 },
  ];
  const anim = el.animate(
    bouncy ? bounce : [
      { transform: `translate(${x}px,${y + 8}px) translate(-50%,-50%) scale(.6)`, opacity: 0 },
      { transform: `translate(${x}px,${y - 10}px) translate(-50%,-50%) scale(1.14)`, opacity: 1, offset: 0.16 },
      { transform: `translate(${x}px,${y - 14}px) translate(-50%,-50%) scale(1)`, opacity: 1, offset: 0.7 },
      { transform: `translate(${x}px,${y - 34}px) translate(-50%,-50%) scale(.96)`, opacity: 0 },
    ],
    { duration: bouncy ? 1500 : 1350, easing: 'ease-out', fill: 'both' },
  );
  anim.onfinish = done;
  anim.oncancel = done;
}

/** 레이어의 남은 조각을 모두 지운다(건너뛰기·판 초기화). */
export function clearParticles(layer: HTMLElement): void {
  for (const anim of layer.getAnimations?.({ subtree: true }) ?? []) anim.cancel();
  layer.replaceChildren();
  live = 0;
}
