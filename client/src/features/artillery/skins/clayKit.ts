/**
 * clay 스킨의 그리기 도구 (D-252·D-254) — 스킨 본체(`claySkin.ts`)와 연출 층(`clayFx.ts`)이 같이 쓴다. 전부 할당 없는 작은 함수다.
 */
export const NAVY = '#22304a';
export const FONT = '"Arial Rounded MT Bold","Jua","Apple SD Gothic Neo","Malgun Gothic",system-ui,sans-serif';
export const CONFETTI = ['#ffd23a', '#ff5a3c', '#3f7fd9', '#ffffff', '#4fd45a', '#d94f8a'];
export const TAU = Math.PI * 2;

/** 결정적 난수 0~1. */
export function hash(a: number, b: number): number {
  let n = (Math.imul(a | 0, 374761393) + Math.imul(b | 0, 668265263)) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
}
export const clamp01 = (t: number) => (t < 0 ? 0 : t > 1 ? 1 : t);
export const ease = (t: number) => 1 - Math.pow(1 - clamp01(t), 3);
/** 살짝 넘쳤다 돌아오는 튕김. */
export const back = (t: number) => {
  const u = clamp01(t) - 1;
  return 1 + 3.2 * u * u * u + 2.2 * u * u;
};
export const mod = (v: number, m: number) => ((v % m) + m) % m;

export function disc(ctx: CanvasRenderingContext2D, x: number, y: number, r: number) {
  ctx.beginPath();
  ctx.arc(x, y, r, 0, TAU);
  ctx.fill();
}
export function ell(ctx: CanvasRenderingContext2D, x: number, y: number, rx: number, ry: number, rot = 0) {
  ctx.beginPath();
  ctx.ellipse(x, y, rx, ry, rot, 0, TAU);
  ctx.fill();
}
export function rr(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  r = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}
/** 뾰족 별(변환 없이 꼭짓점을 직접 찍는다). */
export function star(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, points: number, inner: number, rot: number) {
  ctx.beginPath();
  for (let i = 0; i < points * 2; i++) {
    const a = rot + (i * Math.PI) / points;
    const q = i % 2 ? r * inner : r;
    ctx.lineTo(x + Math.cos(a) * q, y + Math.sin(a) * q);
  }
  ctx.closePath();
  ctx.fill();
}
