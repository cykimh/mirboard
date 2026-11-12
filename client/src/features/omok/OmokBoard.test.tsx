import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import './testPointerEvent';
import { OmokBoard } from './OmokBoard';
import type { OmokPoint } from '@/types/omok';

/** 판은 380×380 px 로 둔다(19줄) — 교차점 간격 20px, (0,0) 교차점이 (10,10). */
const SIZE = 380;
const at = (p: OmokPoint) => ({ clientX: 10 + p.x * 20, clientY: 10 + p.y * 20 });

function setup(over: Partial<React.ComponentProps<typeof OmokBoard>> = {}) {
  const setPreview = vi.fn();
  const onConfirm = vi.fn();
  const props: React.ComponentProps<typeof OmokBoard> = {
    moves: [],
    forbidden: [],
    forbiddenIsMine: false,
    preview: null,
    pending: null,
    myColor: 'BLACK',
    winLine: [],
    showNumbers: false,
    enabled: true,
    singleClick: false,
    setPreview,
    onConfirm,
    ...over,
  };
  const utils = render(<OmokBoard {...props} />);
  const svg = utils.container.querySelector('svg')!;
  svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: SIZE, height: SIZE, right: SIZE, bottom: SIZE, x: 0, y: 0, toJSON: () => ({}) });
  return { ...utils, svg, setPreview, onConfirm, props };
}

describe('OmokBoard — 두 단계 확정 (D-180)', () => {
  it('누르면 가장 가까운 교차점에 미리보기를 둔다(확정하지 않는다)', () => {
    const { svg, setPreview, onConfirm } = setup();
    const p = at({ x: 4, y: 6 });
    fireEvent.pointerDown(svg, { ...p, clientX: p.clientX + 6, clientY: p.clientY - 7, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerUp(svg, { ...p, pointerId: 1, pointerType: 'touch' });
    expect(setPreview).toHaveBeenLastCalledWith({ x: 4, y: 6 });
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('미리보기를 한 번 더 누르면 확정한다', () => {
    const { svg, onConfirm } = setup({ preview: { x: 4, y: 6 } });
    fireEvent.pointerDown(svg, { ...at({ x: 4, y: 6 }), pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerUp(svg, { ...at({ x: 4, y: 6 }), pointerId: 1, pointerType: 'touch' });
    expect(onConfirm).toHaveBeenCalledWith({ x: 4, y: 6 });
  });

  it('손가락으로 끌면 미리보기가 1.5칸 위를 따라가고, 끈 뒤 떼면 확정하지 않는다', () => {
    const { svg, setPreview, onConfirm } = setup({ preview: { x: 4, y: 6 } });
    fireEvent.pointerDown(svg, { ...at({ x: 4, y: 6 }), pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerMove(svg, { ...at({ x: 6, y: 9 }), pointerId: 1, pointerType: 'touch' });
    expect(setPreview).toHaveBeenLastCalledWith({ x: 6, y: 8 }); // 9 − 1.5 = 7.5 → 반올림 8
    fireEvent.pointerUp(svg, { ...at({ x: 6, y: 9 }), pointerId: 1, pointerType: 'touch' });
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('누른 칸 안의 작은 떨림은 끌기가 아니다 — 확정이 취소되지 않는다', () => {
    const { svg, onConfirm } = setup({ preview: { x: 4, y: 6 } });
    const p = at({ x: 4, y: 6 });
    fireEvent.pointerDown(svg, { ...p, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerMove(svg, { clientX: p.clientX + 4, clientY: p.clientY + 3, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerUp(svg, { ...p, pointerId: 1, pointerType: 'touch' });
    expect(onConfirm).toHaveBeenCalledWith({ x: 4, y: 6 });
  });

  it('돌이 있는 자리에는 미리보기를 두지 않는다', () => {
    const { svg, setPreview } = setup({ moves: [{ seat: 0, x: 4, y: 6, timedOut: false }] });
    fireEvent.pointerDown(svg, { ...at({ x: 4, y: 6 }), pointerId: 1, pointerType: 'touch' });
    expect(setPreview).not.toHaveBeenCalled();
  });

  it('내 차례가 아니면(enabled=false) 아무것도 하지 않는다', () => {
    const { svg, setPreview, onConfirm } = setup({ enabled: false, preview: { x: 1, y: 1 } });
    fireEvent.pointerDown(svg, { ...at({ x: 1, y: 1 }), pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerUp(svg, { ...at({ x: 1, y: 1 }), pointerId: 1, pointerType: 'touch' });
    expect(setPreview).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.queryByTestId('om-preview')).toBeNull();
  });

  it('마우스 한 번 클릭 설정이면 마우스 클릭 한 번에 확정하고, 손가락은 그래도 두 단계다', () => {
    const mouse = setup({ singleClick: true });
    fireEvent.pointerDown(mouse.svg, { ...at({ x: 2, y: 3 }), pointerId: 1, pointerType: 'mouse' });
    fireEvent.pointerUp(mouse.svg, { ...at({ x: 2, y: 3 }), pointerId: 1, pointerType: 'mouse' });
    expect(mouse.onConfirm).toHaveBeenCalledWith({ x: 2, y: 3 });
    mouse.unmount();

    const touch = setup({ singleClick: true });
    fireEvent.pointerDown(touch.svg, { ...at({ x: 2, y: 3 }), pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerUp(touch.svg, { ...at({ x: 2, y: 3 }), pointerId: 1, pointerType: 'touch' });
    expect(touch.onConfirm).not.toHaveBeenCalled();
  });

  it('키보드: 화살표로 미리보기를 옮기고 Enter 로 확정한다(시작은 천원 9,9 — D-193)', () => {
    const { svg, setPreview, onConfirm, rerender, props } = setup();
    fireEvent.keyDown(svg, { key: 'ArrowRight' });
    expect(setPreview).toHaveBeenLastCalledWith({ x: 10, y: 9 });
    rerender(<OmokBoard {...props} preview={{ x: 10, y: 9 }} />);
    fireEvent.keyDown(svg, { key: 'Enter' });
    expect(onConfirm).toHaveBeenCalledWith({ x: 10, y: 9 });
  });

  it('돌·금수 ✕·승리 줄을 그린다', () => {
    const { container } = setup({
      moves: [
        { seat: 0, x: 1, y: 1, timedOut: false },
        { seat: 1, x: 2, y: 2, timedOut: false },
      ],
      forbidden: [{ x: 5, y: 5, kind: 'DOUBLE_THREE' }],
      winLine: [
        { x: 0, y: 0 },
        { x: 4, y: 4 },
      ],
    });
    expect(screen.getAllByTestId('om-stone').map((s) => s.getAttribute('data-color'))).toEqual(['BLACK', 'WHITE']);
    expect(container.querySelector('.om-forbidden[data-kind="DOUBLE_THREE"]')).not.toBeNull();
    expect(container.querySelector('.om-winline')).not.toBeNull();
  });
});

describe('OmokBoard — 연출 고도화 (D-194, 그림만)', () => {
  const FIVE = [0, 1, 2, 3, 4].map((i) => ({ x: 3 + i, y: 7 }));
  const moves = FIVE.flatMap((p, i) => [
    { seat: 0, x: p.x, y: p.y, timedOut: false },
    ...(i < 4 ? [{ seat: 1, x: p.x, y: 12, timedOut: false }] : []),
  ]);

  it('5목 — 돌 사이 구간마다 빛줄기 하나, 돌마다 고리 하나를 순서(--om-i)대로 그리고 박자 변수를 판에 넣는다', () => {
    const { container, svg } = setup({ moves, winLine: FIVE, enabled: false });
    const beams = [...container.querySelectorAll<SVGGElement>('.om-beam')];
    expect(beams).toHaveLength(4);
    expect(beams.map((b) => b.style.getPropertyValue('--om-i'))).toEqual(['0', '1', '2', '3']);
    expect(container.querySelectorAll('.om-win-mark')).toHaveLength(5);
    expect(container.querySelectorAll('.om-stone.om-won')).toHaveLength(5);
    expect(svg.style.getPropertyValue('--om-n')).toBe('5');
    expect(svg.style.getPropertyValue('--om-t0')).toBe('0.35');
  });

  it('승리 줄이 없으면(기권·무승부) 빛줄기·고리가 없다', () => {
    const { container, svg } = setup({ moves, winLine: [], enabled: false });
    expect(container.querySelector('.om-beam')).toBeNull();
    expect(container.querySelector('.om-win-mark')).toBeNull();
    expect(svg.style.getPropertyValue('--om-n')).toBe('');
  });

  it('마지막 수에 멈춘 고리, 강하게 둔 수에 빛살·잔상·번쩍임 조각이 붙는다(위협 표시는 없다)', () => {
    const base = moves.slice(0, 2);
    const { container, rerender, props } = setup({ moves: base });
    expect(container.querySelector('.om-last .om-last-halo')).not.toBeNull();
    expect(container.querySelector('.om-slam-rays')).toBeNull();
    const next = [...base, { seat: 0, x: 9, y: 9, timedOut: false }];
    rerender(<OmokBoard {...props} moves={next} slamMoves={new Set([3])} />);
    expect(container.querySelectorAll('.om-slam-rays line')).toHaveLength(10);
    expect(container.querySelector('.om-slam-trail')).not.toBeNull();
    expect(container.querySelector('.om-slam-tint')).not.toBeNull();
    expect(container.querySelector('.om-shock-far')).not.toBeNull();
  });
});

describe('OmokBoard — 연출 표시 (D-192, 그림만)', () => {
  const B = { seat: 0, x: 7, y: 7, timedOut: false };
  const W = { seat: 1, x: 8, y: 8, timedOut: false };

  it('처음 그릴 때는 내려앉지 않고, 한 수가 늘면 그 돌만 내려앉는다', () => {
    const { container, rerender, props } = setup({ moves: [B] });
    expect(container.querySelector('.om-stone.om-drop')).toBeNull();
    rerender(<OmokBoard {...props} moves={[B, W]} />);
    const dropped = container.querySelectorAll('.om-stone.om-drop');
    expect(dropped).toHaveLength(1);
    expect(dropped[0].getAttribute('data-color')).toBe('WHITE');
    expect(container.querySelectorAll('.om-dust')).toHaveLength(8);
  });

  it('여러 수가 한꺼번에 바뀌면(다시 받기) 연출 없이 그린다', () => {
    const { container, rerender, props } = setup({ moves: [] });
    rerender(<OmokBoard {...props} moves={[B, W]} />);
    expect(container.querySelector('.om-drop')).toBeNull();
  });

  it('내가 누른 수는 바로 내려앉고, 확정되면 같은 돌이 이어받아 다시 떨어지지 않는다', () => {
    const { container, rerender, props } = setup({ moves: [] });
    rerender(<OmokBoard {...props} pending={{ x: 7, y: 7 }} />);
    const pending = screen.getByTestId('om-pending');
    expect(pending.getAttribute('class')).toContain('om-drop');
    expect(screen.queryAllByTestId('om-stone')).toHaveLength(0);
    rerender(<OmokBoard {...props} moves={[B]} pending={null} />);
    const placed = screen.getByTestId('om-stone');
    expect(placed).toBe(pending); // 같은 요소 — 연출이 끊기지 않는다
    expect(placed.getAttribute('class')).toContain('om-drop');
    expect(container.querySelectorAll('.om-stone')).toHaveLength(1);
  });

  it('내 금수 자리의 미리보기는 ✕ 에 경고를 싣고 안내선을 그리지 않는다', () => {
    const { container } = setup({
      forbidden: [{ x: 5, y: 5, kind: 'DOUBLE_THREE' }],
      forbiddenIsMine: true,
      preview: { x: 5, y: 5 },
    });
    expect(container.querySelector('.om-forbidden.om-warn')).not.toBeNull();
    expect(container.querySelector('.om-guide')).toBeNull();
    expect(container.querySelector('svg')!.getAttribute('class')).toContain('om-board-warn');
  });

  it('5목 줄의 돌에 순서를 싣고 금가루는 상한(26)을 넘지 않는다', () => {
    const line = [3, 4, 5, 6, 7].map((x) => ({ x, y: 7 }));
    const moves = line.flatMap((p, i) => [
      { seat: 0, ...p, timedOut: false },
      { seat: 1, x: i, y: 0, timedOut: false },
    ]);
    const { container } = setup({ moves: moves.slice(0, 9), winLine: line });
    expect(container.querySelectorAll('.om-stone.om-won')).toHaveLength(5);
    expect(container.querySelectorAll('.om-gold').length).toBeLessThanOrEqual(26);
  });
});

describe('OmokBoard — 강하게 두기 연출 (D-193, 그림만)', () => {
  const B = { seat: 0, x: 7, y: 7, timedOut: false };
  const W = { seat: 1, x: 8, y: 8, timedOut: false };

  it('강하게 둔 수(slamMoves)가 들어오면 그 돌에 강한 연출·충격파·금·돌가루를 그리고 둘레 돌이 들썩인다', () => {
    const { container, rerender, props } = setup({ moves: [B] });
    rerender(<OmokBoard {...props} moves={[B, W]} slamMoves={new Set([2])} />);
    const slammed = container.querySelector('.om-stone.om-slam');
    expect(slammed?.getAttribute('data-color')).toBe('WHITE');
    expect(screen.getByTestId('om-slam-fx')).toBeInTheDocument();
    expect(container.querySelectorAll('.om-shock')).toHaveLength(2);
    expect(container.querySelectorAll('.om-crack')).toHaveLength(6);
    expect(container.querySelectorAll('.om-debris')).toHaveLength(18);
    expect(container.querySelector('svg.om-board')?.getAttribute('class')).toMatch(/om-slam-[01]/);
    expect(container.querySelector('.om-stone[class*="om-jolt-"]')?.getAttribute('data-color')).toBe('BLACK');
  });

  it('보통 수에는 강한 연출이 없다', () => {
    const { container, rerender, props } = setup({ moves: [B] });
    rerender(<OmokBoard {...props} moves={[B, W]} slamMoves={new Set()} />);
    expect(container.querySelector('.om-slam')).toBeNull();
    expect(screen.queryByTestId('om-slam-fx')).toBeNull();
    expect(container.querySelector('svg.om-board')?.getAttribute('class')).toMatch(/om-thud-[01]/);
  });

  it('다시 받기로 여러 수가 한꺼번에 오면 강하게 둔 수여도 다시 내리치지 않는다', () => {
    const { rerender, props } = setup({ moves: [] });
    rerender(<OmokBoard {...props} moves={[B, W]} slamMoves={new Set([1, 2])} />);
    expect(screen.queryByTestId('om-slam-fx')).toBeNull();
  });

  it('내가 강하게 보낸 수는 응답 전에 바로 내리치고, 확정돼도 같은 연출을 이어받는다', () => {
    const { rerender, props } = setup({ moves: [] });
    rerender(<OmokBoard {...props} pending={{ x: 7, y: 7 }} pendingSlam />);
    const pending = screen.getByTestId('om-pending');
    expect(pending.getAttribute('class')).toContain('om-slam');
    const fx = screen.getByTestId('om-slam-fx');
    rerender(<OmokBoard {...props} moves={[B]} pending={null} slamMoves={new Set([1])} />);
    expect(screen.getByTestId('om-stone')).toBe(pending);
    expect(screen.getByTestId('om-slam-fx')).toBe(fx); // 다시 터지지 않는다
  });
});

describe('OmokBoard — 19줄 바둑판 (D-193)', () => {
  it('19줄과 화점 9곳을 그리고, 가장 오른쪽 아래 교차점(18,18)을 누를 수 있다', () => {
    const { container, svg, setPreview } = setup();
    expect(container.querySelectorAll('.om-grid')).toHaveLength(38);
    expect(container.querySelectorAll('.om-star')).toHaveLength(9);
    expect(svg.getAttribute('viewBox')).toBe('-0.5 -0.5 19 19');
    fireEvent.pointerDown(svg, { ...at({ x: 18, y: 18 }), pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerUp(svg, { ...at({ x: 18, y: 18 }), pointerId: 1, pointerType: 'touch' });
    expect(setPreview).toHaveBeenLastCalledWith({ x: 18, y: 18 });
  });
});
