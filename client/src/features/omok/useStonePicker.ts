import { useCallback, useRef } from 'react';
import type { OmokPoint } from '@/types/omok';
import { nearestPoint } from './omokRules';

/** 손가락으로 끌 때 미리보기를 손가락보다 이만큼(칸) 위에 둔다 — 손가락에 가리지 않게(D-180). */
export const TOUCH_LIFT_CELLS = 1.5;

interface Options {
  /** 지금 둘 수 있는가(내 차례·연결됨·판 진행 중). 아니면 아무것도 안 한다. */
  enabled: boolean;
  preview: OmokPoint | null;
  /** 이 칸이 빈칸인가(돌이 있는 자리에는 미리보기를 두지 않는다). */
  isEmpty: (p: OmokPoint) => boolean;
  setPreview: (p: OmokPoint | null) => void;
  /** 확정 — 같은 미리보기를 다시 누르면 부른다. */
  confirm: (p: OmokPoint) => void;
  /** 화면 좌표 → 판 좌표(교차점 간격 1, 원점 = (0,0) 교차점). */
  toBoard: (clientX: number, clientY: number) => { fx: number; fy: number; cell: number } | null;
  /** 데스크톱 "한 번 클릭으로 두기"(이 기기 설정). 마우스에만 적용한다 — 휴대폰은 늘 두 단계. */
  singleClick?: boolean;
}

/**
 * D-180 — 두 단계 확정 착수. 판 어디를 눌러도 가장 가까운 교차점에 미리보기가 뜨고, **미리보기 위를 다시 누르면** 확정한다.
 * 누른 채 끌면 미리보기가 따라온다 — 손가락이면 끄는 동안 {@link TOUCH_LIFT_CELLS} 칸 위(손가락에 가리지 않게). 확정 버튼(게임판 아래 [여기에 두기])은 게임판이 따로
 * 그린다. 판정은 하지 않는다 — 금수·빈칸 여부의 권위는 서버다(여기서는 돌 위에 미리보기를 두지 않을 뿐).
 */
export function useStonePicker({ enabled, preview, isEmpty, setPreview, confirm, toBoard, singleClick = false }: Options) {
  const drag = useRef<{
    pointerId: number;
    touch: boolean;
    /** 누른 칸(들어 올리기 전). 손가락이 이 칸을 벗어나야 끌기로 본다 — 작은 떨림으로 확정이 취소되지 않게. */
    downAt: OmokPoint | null;
    startedOn: OmokPoint | null;
    moved: boolean;
  } | null>(null);

  const pointAt = useCallback(
    (clientX: number, clientY: number, touch: boolean): OmokPoint | null => {
      const b = toBoard(clientX, clientY);
      if (!b) return null;
      return nearestPoint(b.fx, touch ? b.fy - TOUCH_LIFT_CELLS : b.fy);
    },
    [toBoard],
  );

  const onPointerDown = useCallback(
    (e: React.PointerEvent) => {
      if (!enabled) return;
      const touch = e.pointerType !== 'mouse';
      // 누른 곳 자체(들어 올리기 전)가 미리보기 위면 확정 후보 — 떼는 순간 끌지 않았으면 확정한다.
      const raw = pointAt(e.clientX, e.clientY, false);
      const onPreview = preview !== null && raw !== null && raw.x === preview.x && raw.y === preview.y;
      drag.current = { pointerId: e.pointerId, touch, downAt: raw, startedOn: onPreview ? preview : null, moved: false };
      (e.currentTarget as Element).setPointerCapture?.(e.pointerId);
      // 누른 자리 그대로 — 손가락 위로 올리는 것은 끌기 시작한 뒤부터다(그냥 누르면 누른 칸에 뜬다).
      if (!onPreview && raw && isEmpty(raw)) setPreview(raw);
    },
    [enabled, pointAt, preview, isEmpty, setPreview],
  );

  const onPointerMove = useCallback(
    (e: React.PointerEvent) => {
      const d = drag.current;
      if (!enabled) return;
      if (!d) {
        // 마우스를 올려 둔 것만으로도 미리보기 — 데스크톱.
        if (e.pointerType === 'mouse') {
          const p = pointAt(e.clientX, e.clientY, false);
          if (p && isEmpty(p) && (preview === null || p.x !== preview.x || p.y !== preview.y)) setPreview(p);
        }
        return;
      }
      if (d.pointerId !== e.pointerId) return;
      const raw = pointAt(e.clientX, e.clientY, false);
      if (!raw) return;
      if (!d.moved && d.downAt && raw.x === d.downAt.x && raw.y === d.downAt.y) return; // 아직 누른 칸 안
      d.moved = true;
      d.startedOn = null;
      const p = d.touch ? pointAt(e.clientX, e.clientY, true) : raw;
      if (p && isEmpty(p) && (preview === null || p.x !== preview.x || p.y !== preview.y)) setPreview(p);
    },
    [enabled, pointAt, preview, isEmpty, setPreview],
  );

  const onPointerUp = useCallback(
    (e: React.PointerEvent) => {
      const d = drag.current;
      drag.current = null;
      if (!enabled || !d || d.pointerId !== e.pointerId) return;
      if (d.startedOn && !d.moved) {
        confirm(d.startedOn);
        return;
      }
      if (singleClick && !d.touch && !d.moved) {
        const p = pointAt(e.clientX, e.clientY, false);
        if (p && isEmpty(p)) confirm(p);
      }
    },
    [enabled, confirm, singleClick, pointAt, isEmpty],
  );

  const onPointerCancel = useCallback(() => {
    drag.current = null;
  }, []);

  return { onPointerDown, onPointerMove, onPointerUp, onPointerCancel };
}
