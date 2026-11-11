import { useEffect, useRef } from 'react';
import { prefersReducedMotion } from './yachtTheme';

/** 한 번에 그리는 낱알 상한 — 휴대폰(좁은 화면)은 더 적게(60fps). */
export const BURST_CAP_WIDE = 160;
export const BURST_CAP_NARROW = 80;

const PALETTE = ['#fff6d0', '#f6e2a4', '#e9c86a', '#d6b25e', '#b8892f', '#ffffff'];

interface Props {
  /** 터지는 자리(뷰포트 좌표). */
  x: number;
  y: number;
  count: number;
  /** 세기 — 1 이 요트 축하, 0.4 안팎이 칩이 꽂히는 불티. */
  power?: number;
  delayMs?: number;
}

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  size: number;
  rot: number;
  vr: number;
  flip: number;
  vf: number;
  life: number;
  age: number;
  color: string;
  round: boolean;
}

/**
 * 금가루 폭발(D-203) — 직접 짠 작은 Canvas 입자(외부 라이브러리·그림 없음). 화면 전체를 덮는 고정 캔버스 한 장에 그리고
 * `pointer-events: none` 이라 조작을 막지 않는다. 낱알은 상한이 있고 다 꺼지면 스스로 멈춘다. '동작 줄이기'·캔버스 없는 환경(테스트)은
 * 아무것도 그리지 않는다. 같은 자리에서 다시 터뜨리려면 `key` 를 바꿔 다시 마운트한다.
 */
export function GoldBurst({ x, y, count, power = 1, delayMs = 0 }: Props) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    // jsdom 은 캔버스가 없다(getContext 가 경고만 낸다) — 테스트에서는 그리지 않는다.
    if (!canvas || prefersReducedMotion() || /jsdom/i.test(navigator.userAgent)) return;
    let ctx: CanvasRenderingContext2D | null = null;
    try {
      ctx = canvas.getContext('2d');
    } catch {
      ctx = null;
    }
    if (!ctx) return;
    const context = ctx;
    const w = window.innerWidth;
    const h = window.innerHeight;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    const n = Math.min(count, w < 480 ? BURST_CAP_NARROW : BURST_CAP_WIDE);
    const particles: Particle[] = Array.from({ length: n }, () => {
      const angle = Math.random() * Math.PI * 2;
      const speed = (2.5 + Math.random() * 8.5) * power;
      return {
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed * 0.8 - 3.2 * power,
        size: 2.5 + Math.random() * 4.5,
        rot: Math.random() * Math.PI,
        vr: (Math.random() - 0.5) * 0.4,
        flip: Math.random() * Math.PI,
        vf: 0.15 + Math.random() * 0.25,
        life: (850 + Math.random() * 1100) * (0.6 + power * 0.4),
        age: 0,
        color: PALETTE[Math.floor(Math.random() * PALETTE.length)],
        round: Math.random() < 0.3,
      };
    });
    let raf = 0;
    let last = 0;
    let start = 0;
    const frame = (now: number) => {
      if (start === 0) start = now;
      if (now - start < delayMs) {
        raf = window.requestAnimationFrame(frame);
        return;
      }
      const dt = last === 0 ? 1 : Math.min(3, (now - last) / 16.67);
      last = now;
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      context.clearRect(0, 0, w, h);
      let alive = 0;
      for (const p of particles) {
        p.age += dt * 16.67;
        if (p.age >= p.life) continue;
        alive += 1;
        const drag = Math.pow(0.965, dt);
        p.vx *= drag;
        p.vy = p.vy * drag + 0.2 * dt;
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.rot += p.vr * dt;
        p.flip += p.vf * dt;
        const t = p.age / p.life;
        context.globalAlpha = t < 0.7 ? 1 : 1 - (t - 0.7) / 0.3;
        context.fillStyle = p.color;
        context.setTransform(dpr, 0, 0, dpr, p.x * dpr, p.y * dpr);
        context.rotate(p.rot);
        if (p.round) {
          context.beginPath();
          context.arc(0, 0, p.size * 0.45, 0, Math.PI * 2);
          context.fill();
        } else {
          // 금박 조각 — 뒤집히며 반짝인다(가로 폭이 접혔다 펴진다).
          const sx = Math.max(0.12, Math.abs(Math.cos(p.flip)));
          context.fillRect((-p.size * sx) / 2, -p.size / 2, p.size * sx, p.size);
        }
      }
      context.globalAlpha = 1;
      if (alive > 0) raf = window.requestAnimationFrame(frame);
      else {
        context.setTransform(1, 0, 0, 1, 0, 0);
        context.clearRect(0, 0, canvas.width, canvas.height);
      }
    };
    raf = window.requestAnimationFrame(frame);
    return () => window.cancelAnimationFrame(raf);
    // 마운트 한 번에 한 번 터진다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return <canvas ref={ref} className="yt-burst" aria-hidden="true" />;
}
