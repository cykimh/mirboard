import { useCallback, useId, useMemo, useRef, useState } from 'react';
import { BOARD_SIZE, type OmokForbiddenPoint, type OmokMove, type OmokPoint, type OmokStone } from '@/types/omok';
import { coordName, forbiddenAt, key, stonesOf } from './omokRules';
import { GLINT_PATH, OmokDefs, StoneBody, StoneShadow, artId } from './OmokArt';
import { useStonePicker } from './useStonePicker';

/** 화점 — 19줄 바둑판처럼 4·10·16 줄이 만나는 9곳(D-193). */
const STAR_LINES = [3, 9, 15];
const STARS: OmokPoint[] = STAR_LINES.flatMap((y) => STAR_LINES.map((x) => ({ x, y })));
const CENTER: OmokPoint = { x: (BOARD_SIZE - 1) / 2, y: (BOARD_SIZE - 1) / 2 };
const LINES = Array.from({ length: BOARD_SIZE }, (_, i) => i);

/** 입자 수 상한(D-192) — 휴대폰에서도 가볍게. 돌 하나에 먼지 8, 5목에 금가루 26. */
const DUST_COUNT = 8;
const GOLD_COUNT = 26;
/** D-193 강하게 두기 — 돌가루 18, 나뭇결 금 6줄. */
const DEBRIS_COUNT = 18;
const CRACK_COUNT = 6;
/** D-194 — 강하게 두기 방사 빛살 10줄. */
const RAY_COUNT = 10;
/** D-194 — 5목 연출이 시작하는 때(초): 마지막 돌이 내려앉은 뒤. 강하게 둔 수로 끝나면 그 연출이 가라앉은 뒤. */
const WIN_START_SEC = 0.35;
const WIN_START_AFTER_SLAM_SEC = 0.7;

/** 씨앗 고정 난수 — 입자 방향을 렌더마다 같게 한다(렌더는 순수해야 한다). */
function seeded(seed: number) {
  let s = Math.imul(seed + 1, 0x9e3779b1) >>> 0 || 1;
  return () => (s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296;
}

/** 돌이 닿는 순간 사방으로 튀는 먼지 — 방향은 고르게, 거리·크기만 흩는다. */
function dustOf(seed: number) {
  const rnd = seeded(seed);
  return Array.from({ length: DUST_COUNT }, (_, i) => {
    const angle = (i / DUST_COUNT) * Math.PI * 2 + rnd() * 0.7;
    const dist = 0.62 + rnd() * 0.55;
    return {
      dx: Math.cos(angle) * dist,
      dy: Math.sin(angle) * dist * 0.75 - 0.08,
      r: 0.022 + rnd() * 0.03,
      delay: rnd() * 0.07,
    };
  });
}

/** 강하게 두기 — 돌가루·나무 조각이 멀리 튀었다가 떨어진다. 짝수는 가루(원), 홀수는 조각(사각). */
function debrisOf(seed: number) {
  const rnd = seeded(seed * 7 + 3);
  return Array.from({ length: DEBRIS_COUNT }, (_, i) => {
    const angle = (i / DEBRIS_COUNT) * Math.PI * 2 + rnd() * 0.5;
    const dist = 1.1 + rnd() * 1.5;
    return {
      dx: Math.cos(angle) * dist,
      dy: Math.sin(angle) * dist * 0.8,
      r: 0.07 + rnd() * 0.07,
      rot: Math.round(rnd() * 720 - 360),
      delay: rnd() * 0.06,
      chip: i % 2 === 1,
    };
  });
}

/** 강하게 두기 — 돌 둘레에서 뻗는 나뭇결 금(잠깐 그어졌다 사라진다). */
function cracksOf(seed: number) {
  const rnd = seeded(seed * 13 + 5);
  return Array.from({ length: CRACK_COUNT }, (_, i) => {
    const a = (i / CRACK_COUNT) * Math.PI * 2 + rnd() * 0.6;
    const r0 = 0.5;
    const r1 = 1.1 + rnd() * 0.7;
    const bend = (rnd() - 0.5) * 0.5;
    const mx = Math.cos(a + bend) * ((r0 + r1) / 2);
    const my = Math.sin(a + bend) * ((r0 + r1) / 2);
    return `M${(Math.cos(a) * r0).toFixed(3)} ${(Math.sin(a) * r0).toFixed(3)} L${mx.toFixed(3)} ${my.toFixed(3)} L${(
      Math.cos(a) * r1
    ).toFixed(3)} ${(Math.sin(a) * r1).toFixed(3)}`;
  });
}

/** 강하게 두기 — 닿는 순간 사방으로 뻗는 짧은 빛살(D-194). 길이만 흩는다. */
function raysOf(seed: number) {
  const rnd = seeded(seed * 17 + 11);
  return Array.from({ length: RAY_COUNT }, (_, i) => {
    const a = (i / RAY_COUNT) * Math.PI * 2 + rnd() * 0.3;
    const r0 = 0.75;
    const r1 = 1.5 + rnd() * 0.9;
    return {
      x1: Math.cos(a) * r0,
      y1: Math.sin(a) * r0,
      x2: Math.cos(a) * r1,
      y2: Math.sin(a) * r1,
    };
  });
}

/** 5목 줄을 따라 피어오르는 금가루 — 줄이 그어지는 순서대로(t) 터진다. */
function goldOf(a: OmokPoint, b: OmokPoint) {
  const rnd = seeded(a.x * 31 + a.y * 17 + b.x * 7 + b.y);
  return Array.from({ length: GOLD_COUNT }, (_, i) => {
    const t = (i + rnd()) / GOLD_COUNT;
    const angle = rnd() * Math.PI * 2;
    const dist = 0.5 + rnd() * 1.1;
    return {
      x: a.x + (b.x - a.x) * t,
      y: a.y + (b.y - a.y) * t,
      dx: Math.cos(angle) * dist,
      dy: Math.sin(angle) * dist - 0.5,
      r: 0.025 + rnd() * 0.04,
      t,
    };
  });
}

const vars = (v: Record<string, number>) => v as unknown as React.CSSProperties;

interface Props {
  moves: OmokMove[];
  /** 흑 차례의 금수 자리(서버 계산). 흑 차례가 아니면 빈 목록. */
  forbidden: OmokForbiddenPoint[];
  /** 금수 ✕ 를 붉게(내가 흑이고 내 차례) 그릴지. */
  forbiddenIsMine: boolean;
  preview: OmokPoint | null;
  pending: OmokPoint | null;
  /** D-193 — 내가 보낸 수가 강하게 두기인가(응답 전에도 바로 내리친다). */
  pendingSlam?: boolean;
  /** D-193 — 이 세션에서 받은 강하게 둔 수의 번호(1부터). */
  slamMoves?: ReadonlySet<number>;
  /** D-193 — 강하게 두기를 골라 둔 상태(미리보기 돌이 달아오른다). */
  slamArmed?: boolean;
  /** 미리보기·흐린 돌의 색 — 내 색. */
  myColor: OmokStone | null;
  winLine: OmokPoint[];
  showNumbers: boolean;
  /** 둘 수 있는가 — 내 차례·연결됨·진행 중. */
  enabled: boolean;
  singleClick: boolean;
  setPreview: (p: OmokPoint | null) => void;
  onConfirm: (p: OmokPoint) => void;
}

interface ShownStone extends OmokPoint {
  color: OmokStone;
  /** 내가 눌러 서버 응답을 기다리는 수. */
  pending: boolean;
}

/**
 * 오목 판 (D-180, 19줄 D-193). SVG 하나 — 교차점 361개를 DOM 요소로 만들지 않고 누른 화면 좌표를 가장 가까운 교차점으로 맞춘다.
 * 좌표계는 교차점 간격 1, (0,0) 교차점이 원점이고 판 가장자리에 반 칸 여백이 있다(`viewBox` −0.5 … 18.5).
 *
 * <p>두 단계 확정: 누르면 미리보기(뜬 돌 + 그 줄·열 안내선), 미리보기를 다시 누르면 확정. 끌면 미리보기가 따라온다
 * ({@link useStonePicker}). 키보드: 화살표로 미리보기를 옮기고 Enter·Space 로 확정한다.
 *
 * <p>D-192 — 돌·표식은 `translate` 한 바깥 `<g>` 안의 `.om-in` 이 연출을 받는다(돌 중심이 원점). 납작한 원(`.om-flat`)과 그림 조각
 * (`.om-art`)을 같이 그리고 테마 클래스가 고른다. 내가 누른 수(`pending`)는 서버 응답을 기다리지 않고 바로 내려앉고, 확정되면 같은
 * 요소가 이어받아 다시 떨어지지 않는다. 연출은 그림만 — 입력·판정은 그대로다.
 */
export function OmokBoard({
  moves,
  forbidden,
  forbiddenIsMine,
  preview,
  pending,
  pendingSlam = false,
  slamMoves,
  slamArmed = false,
  myColor,
  winLine,
  showNumbers,
  enabled,
  singleClick,
  setPreview,
  onConfirm,
}: Props) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const id = artId(useId());
  const stones = useMemo(() => stonesOf(moves), [moves]);
  const last = moves.length > 0 ? moves[moves.length - 1] : null;

  const isEmpty = useCallback(
    (p: OmokPoint) => !stones.has(key(p)) && !(pending && pending.x === p.x && pending.y === p.y),
    [stones, pending],
  );
  const toBoard = useCallback((clientX: number, clientY: number) => {
    const svg = svgRef.current;
    if (!svg) return null;
    const r = svg.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return null;
    return {
      fx: ((clientX - r.left) / r.width) * BOARD_SIZE - 0.5,
      fy: ((clientY - r.top) / r.height) * BOARD_SIZE - 0.5,
      cell: r.width / BOARD_SIZE,
    };
  }, []);
  const picker = useStonePicker({
    enabled,
    preview,
    isEmpty,
    setPreview,
    confirm: onConfirm,
    toBoard,
    singleClick,
  });

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (!enabled) return;
    const at = preview ?? last ?? CENTER;
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [-1, 0],
      ArrowRight: [1, 0],
      ArrowUp: [0, -1],
      ArrowDown: [0, 1],
    };
    if (delta[e.key]) {
      e.preventDefault();
      const [dx, dy] = delta[e.key];
      const next = {
        x: Math.min(BOARD_SIZE - 1, Math.max(0, at.x + dx)),
        y: Math.min(BOARD_SIZE - 1, Math.max(0, at.y + dy)),
      };
      setPreview(next);
    } else if ((e.key === 'Enter' || e.key === ' ') && preview && isEmpty(preview)) {
      e.preventDefault();
      onConfirm(preview);
    } else if (e.key === 'Escape') {
      setPreview(null);
    }
  };

  // 판에 보이는 돌 = 확정된 수 + 내가 방금 눌러 응답을 기다리는 수.
  const shown = useMemo(() => {
    const list: ShownStone[] = moves.map((m, i) => ({
      x: m.x,
      y: m.y,
      color: i % 2 === 0 ? 'BLACK' : 'WHITE',
      pending: false,
    }));
    if (pending && myColor && !stones.has(key(pending)))
      list.push({ x: pending.x, y: pending.y, color: myColor, pending: true });
    return list;
  }, [moves, pending, myColor, stones]);

  // 방금 놓인 돌 하나만 내려앉는다 — 다시 받기(resync)로 여러 개가 한꺼번에 바뀌면 연출 없이 그린다. 이전 값과 견주는 것이라
  // 렌더 중에 상태를 맞춘다(effect 로 미루면 돌이 한 프레임 먼저 보였다가 다시 떨어진다).
  const tail = shown.length > 0 ? shown[shown.length - 1] : null;
  const tailKey = tail ? key(tail) : null;
  // D-193 — 강하게 두기면 같은 자리에서 더 크게(내 수는 보낸 즉시, 남의 수는 STONE_PLACED.slam 을 받은 때). 확정돼도 이어받아 다시
  // 내리치지 않는다.
  const [track, setTrack] = useState<{
    len: number;
    tailKey: string | null;
    drop: OmokPoint | null;
    seq: number;
    slam: boolean;
  }>({
    len: shown.length,
    tailKey,
    drop: null,
    seq: 0,
    slam: false,
  });
  if (track.len !== shown.length || track.tailKey !== tailKey) {
    const grew = tail !== null && shown.length === track.len + 1;
    const slam = grew && (tail.pending ? pendingSlam : (slamMoves?.has(moves.length) ?? false));
    setTrack({
      len: shown.length,
      tailKey,
      drop: grew ? { x: tail.x, y: tail.y } : null,
      seq: track.seq + (grew ? 1 : 0),
      slam,
    });
  }
  const dropKey = track.drop ? key(track.drop) : null;
  const slamAt = track.slam ? track.drop : null;
  const debris = useMemo(() => (track.slam ? debrisOf(track.seq) : []), [track.slam, track.seq]);
  const cracks = useMemo(() => (track.slam ? cracksOf(track.seq) : []), [track.slam, track.seq]);
  const rays = useMemo(() => (track.slam ? raysOf(track.seq) : []), [track.slam, track.seq]);

  const winIndex = useMemo(() => new Map(winLine.map((p, i) => [key(p), i])), [winLine]);
  const dust = useMemo(() => dustOf(track.seq), [track.seq]);
  const winFrom = winLine.length >= 2 ? winLine[0] : null;
  const winTo = winLine.length >= 2 ? winLine[winLine.length - 1] : null;
  const gold = useMemo(() => (winFrom && winTo ? goldOf(winFrom, winTo) : []), [winFrom, winTo]);

  const showPreview = enabled && preview !== null && isEmpty(preview);
  // 내 금수 자리를 눌렀다 — 뜬 돌 대신 ✕ 가 고개를 젓는다.
  const warn = showPreview && forbiddenIsMine ? forbiddenAt(forbidden, preview) : null;
  const label = `오목 판 — ${moves.length}수${last ? `, 마지막 ${coordName(last)}` : ''}${
    showPreview && preview ? `, 미리보기 ${coordName(preview)}` : ''
  }`;

  return (
    <svg
      ref={svgRef}
      className={`om-board${enabled ? ' om-board-active' : ''}${
        track.seq > 0 ? ` om-${track.slam ? 'slam' : 'thud'}-${track.seq % 2}` : ''
      }${warn ? ' om-board-warn' : ''}`}
      style={
        winLine.length >= 2
          ? vars({ '--om-n': winLine.length, '--om-t0': track.slam ? WIN_START_AFTER_SLAM_SEC : WIN_START_SEC })
          : undefined
      }
      viewBox={`-0.5 -0.5 ${BOARD_SIZE} ${BOARD_SIZE}`}
      role="img"
      aria-label={label}
      tabIndex={enabled ? 0 : -1}
      onKeyDown={onKeyDown}
      onPointerDown={picker.onPointerDown}
      onPointerMove={picker.onPointerMove}
      onPointerUp={picker.onPointerUp}
      onPointerCancel={picker.onPointerCancel}
    >
      <OmokDefs id={id} />
      <rect className="om-board-bg" x={-0.5} y={-0.5} width={BOARD_SIZE} height={BOARD_SIZE} />
      {LINES.map((i) => (
        <g key={i}>
          <line className="om-grid" x1={0} y1={i} x2={BOARD_SIZE - 1} y2={i} />
          <line className="om-grid" x1={i} y1={0} x2={i} y2={BOARD_SIZE - 1} />
        </g>
      ))}
      <rect className="om-art om-board-edge" x={0} y={0} width={BOARD_SIZE - 1} height={BOARD_SIZE - 1} />
      {STARS.map((p) => (
        <circle key={key(p)} className="om-star" cx={p.x} cy={p.y} r={0.09} />
      ))}

      {showPreview && preview && !warn && (
        <g className="om-guide" aria-hidden="true">
          <line x1={-0.5} y1={preview.y} x2={BOARD_SIZE - 0.5} y2={preview.y} />
          <line x1={preview.x} y1={-0.5} x2={preview.x} y2={BOARD_SIZE - 0.5} />
        </g>
      )}

      {/* 나뭇결 파문 — 돌 아래 층. 놓일 때마다 새로 그려 다시 퍼진다. */}
      {track.drop && (
        <g
          key={`r${track.seq}`}
          className="om-art"
          transform={`translate(${track.drop.x} ${track.drop.y})`}
          aria-hidden="true"
        >
          <circle className="om-impact" r={0.62} fill={`url(#${id}-glow)`} />
          <circle className="om-ripple" r={0.46} />
          <circle className="om-ripple om-ripple-2" r={0.46} />
          <circle className="om-ripple om-ripple-3" r={0.46} />
          {/* D-194 — 물결 마루가 등불을 받는 쪽(왼쪽 위)만 밝은 호. */}
          <circle className="om-fx om-ripple-light" r={0.46} pathLength={1} />
        </g>
      )}

      {/* D-193 강하게 두기 — 판에 금이 가고 충격파 두 겹이 퍼진다(돌 아래 층). */}
      {slamAt && (
        <g
          key={`s${track.seq}`}
          className="om-slam-fx"
          transform={`translate(${slamAt.x} ${slamAt.y})`}
          aria-hidden="true"
          data-testid="om-slam-fx"
        >
          <g className="om-art">
            {cracks.map((d, i) => (
              <path key={i} className="om-crack" d={d} pathLength={1} />
            ))}
            <circle className="om-slam-flash" r={1.3} fill={`url(#${id}-glow)`} />
          </g>
          <circle className="om-shock" r={0.5} />
          <circle className="om-shock om-shock-2" r={0.5} />
          {/* D-194 — 멀리 번지는 가는 충격파 + 방사 빛살 + 낙하 잔상. */}
          <circle className="om-fx om-shock-far" r={0.5} />
          <g className="om-fx om-slam-rays">
            {rays.map((r, i) => (
              <line key={i} x1={r.x1} y1={r.y1} x2={r.x2} y2={r.y2} />
            ))}
          </g>
          <line className="om-fx om-slam-trail" x1={0.1} y1={-3.2} x2={0} y2={-0.3} />
          <circle className="om-slam-calm" r={0.62} />
        </g>
      )}

      {/* D-194 강하게 두기 — 닿는 순간 판 전체가 주홍빛으로 한 번 번쩍인다(돌 아래 층, 불투명도만). */}
      {slamAt && (
        <rect
          key={`t${track.seq}`}
          className="om-fx om-slam-tint"
          x={-0.5}
          y={-0.5}
          width={BOARD_SIZE}
          height={BOARD_SIZE}
          aria-hidden="true"
        />
      )}

      {forbidden.map((f) => {
        const warned = warn !== null && warn.x === f.x && warn.y === f.y;
        return (
          <g
            key={`f${key(f)}`}
            className={`om-forbidden${forbiddenIsMine ? ' om-forbidden-mine' : ''}${warned ? ' om-warn' : ''}`}
            data-kind={f.kind}
            transform={`translate(${f.x} ${f.y})`}
            aria-hidden="true"
          >
            <g className="om-in">
              <circle className="om-art om-forbidden-ring" r={0.28} />
              <line x1={-0.18} y1={-0.18} x2={0.18} y2={0.18} />
              <line x1={-0.18} y1={0.18} x2={0.18} y2={-0.18} />
            </g>
          </g>
        );
      })}

      {/* 그림자는 돌보다 아래 층 — 옆 돌을 덮지 않게. */}
      <g aria-hidden="true">
        {shown.map((m) => (
          <g
            key={key(m)}
            className={key(m) === dropKey ? (track.slam ? 'om-drop om-slam' : 'om-drop') : undefined}
            transform={`translate(${m.x} ${m.y})`}
          >
            <StoneShadow id={id} />
          </g>
        ))}
      </g>

      {shown.map((m, i) => {
        const k = key(m);
        const won = winIndex.get(k);
        const slammed = k === dropKey && track.slam;
        // 강하게 두면 둘레 두 칸 안의 돌이 한 번 들썩인다 — 같은 연출을 다시 틀려고 이름 둘을 번갈아 쓴다.
        const near =
          slamAt && !slammed ? Math.max(Math.abs(m.x - slamAt.x), Math.abs(m.y - slamAt.y)) : Number.POSITIVE_INFINITY;
        const jolt = near <= 2;
        const style: Record<string, number> = {};
        if (won !== undefined) style['--om-i'] = won;
        if (jolt) style['--om-near'] = near;
        return (
          <g
            key={k}
            className={`om-stone om-stone-${m.color === 'BLACK' ? 'black' : 'white'}${k === dropKey ? ' om-drop' : ''}${
              slammed ? ' om-slam' : ''
            }${jolt ? ` om-jolt-${track.seq % 2}` : ''}${m.pending ? ' om-pending' : ''}${won !== undefined ? ' om-won' : ''}`}
            style={won !== undefined || jolt ? vars(style) : undefined}
            transform={`translate(${m.x} ${m.y})`}
            data-testid={m.pending ? 'om-pending' : 'om-stone'}
            data-color={m.color}
            aria-hidden={m.pending ? 'true' : undefined}
          >
            <g className="om-in">
              <StoneBody id={id} color={m.color} seed={i} />
            </g>
          </g>
        );
      })}

      {/* 먼지 — 돌 위 층. 닿는 순간 한 번 튀고 사라진다. */}
      {track.drop && (
        <g
          key={`d${track.seq}`}
          className="om-art"
          transform={`translate(${track.drop.x} ${track.drop.y})`}
          aria-hidden="true"
        >
          {dust.map((d, i) => (
            <circle
              key={i}
              className="om-dust"
              r={d.r}
              style={vars({ '--om-dx': d.dx, '--om-dy': d.dy, '--om-delay': d.delay })}
            />
          ))}
        </g>
      )}

      {/* D-193 강하게 두기 — 돌가루·나무 조각이 멀리 튀었다 떨어진다(돌 위 층). */}
      {slamAt && (
        <g
          key={`b${track.seq}`}
          className="om-art"
          transform={`translate(${slamAt.x} ${slamAt.y})`}
          aria-hidden="true"
        >
          {debris.map((d, i) =>
            d.chip ? (
              <rect
                key={i}
                className="om-debris om-debris-chip"
                x={-d.r}
                y={-d.r * 0.6}
                width={d.r * 2}
                height={d.r * 1.2}
                style={vars({ '--om-dx': d.dx, '--om-dy': d.dy, '--om-rot': d.rot, '--om-delay': d.delay })}
              />
            ) : (
              <circle
                key={i}
                className="om-debris"
                r={d.r}
                style={vars({ '--om-dx': d.dx, '--om-dy': d.dy, '--om-rot': d.rot, '--om-delay': d.delay })}
              />
            ),
          )}
        </g>
      )}

      {last && !showNumbers && (
        <g
          key={`l${moves.length}`}
          className={`om-last om-last-on-${moves.length % 2 === 1 ? 'black' : 'white'}`}
          transform={`translate(${last.x} ${last.y})`}
          aria-hidden="true"
        >
          <circle className="om-art om-last-ring" r={0.11} />
          {/* D-194 — 번짐이 끝나면 가는 고리 하나로 멈춘다. */}
          <circle className="om-fx om-last-halo" r={0.2} />
          <circle className="om-last-dot" r={0.11} />
        </g>
      )}

      {winLine.length >= 2 && (
        <g aria-hidden="true">
          <line
            className="om-art om-winline-groove"
            x1={winLine[0].x}
            y1={winLine[0].y}
            x2={winLine[winLine.length - 1].x}
            y2={winLine[winLine.length - 1].y}
            pathLength={1}
          />
          <line
            className="om-art om-winline-glow"
            x1={winLine[0].x}
            y1={winLine[0].y}
            x2={winLine[winLine.length - 1].x}
            y2={winLine[winLine.length - 1].y}
          />
          <line
            className="om-winline"
            x1={winLine[0].x}
            y1={winLine[0].y}
            x2={winLine[winLine.length - 1].x}
            y2={winLine[winLine.length - 1].y}
            pathLength={1}
          />
          {/* D-194 — 구간 빛줄기가 돌에서 돌로 차례로 켜졌다 스러진다(금 상감 줄은 남는다). */}
          {winLine.slice(1).map((p, i) => (
            <g key={`b${key(p)}`} className="om-fx om-beam" style={{ '--om-i': i } as React.CSSProperties}>
              <line className="om-beam-wide" x1={winLine[i].x} y1={winLine[i].y} x2={p.x} y2={p.y} pathLength={1} />
              <line className="om-beam-core" x1={winLine[i].x} y1={winLine[i].y} x2={p.x} y2={p.y} pathLength={1} />
            </g>
          ))}
          {/* D-194 — 빛이 닿는 돌마다 고리가 한 번 번지고, 끝나면 이긴 돌 둘레에 가는 금 고리가 남는다. */}
          {winLine.map((p, i) => (
            <g
              key={`h${key(p)}`}
              className="om-fx om-win-mark"
              transform={`translate(${p.x} ${p.y})`}
              style={{ '--om-i': i } as React.CSSProperties}
            >
              <circle className="om-win-burst" r={0.5} />
              <circle className="om-win-ring-groove" r={0.49} />
              <circle className="om-win-ring" r={0.49} />
            </g>
          ))}
          {winLine.map((p, i) => (
            <g key={`g${key(p)}`} className="om-art" transform={`translate(${p.x - 0.2} ${p.y - 0.22})`}>
              <path className="om-glint" style={{ '--om-i': i } as React.CSSProperties} d={GLINT_PATH} />
            </g>
          ))}
          {/* 줄을 긋는 불티 하나 + 금가루. */}
          {winFrom && winTo && (
            <circle
              className="om-art om-spark"
              fill={`url(#${id}-glow)`}
              cx={winFrom.x}
              cy={winFrom.y}
              r={0.16}
              style={vars({ '--om-lx': winTo.x - winFrom.x, '--om-ly': winTo.y - winFrom.y })}
            />
          )}
          <g className="om-art">
            {gold.map((g, i) => (
              <circle
                key={i}
                className="om-gold"
                cx={g.x}
                cy={g.y}
                r={g.r}
                style={vars({ '--om-dx': g.dx, '--om-dy': g.dy, '--om-t': g.t })}
              />
            ))}
          </g>
        </g>
      )}

      {/* 수 번호는 승리 줄 위에 — 줄이 흑돌 위 번호를 가리지 않게. */}
      {showNumbers &&
        moves.map((m, i) => (
          <text
            key={`n${key(m)}`}
            className={`om-num om-num-${i % 2 === 0 ? 'black' : 'white'}`}
            x={m.x}
            y={m.y}
            dominantBaseline="central"
            textAnchor="middle"
          >
            {i + 1}
          </text>
        ))}

      {showPreview && preview && myColor && (
        <g
          key={`p${key(preview)}`}
          className={`om-stone om-stone-${myColor === 'BLACK' ? 'black' : 'white'} om-preview${warn ? ' om-preview-warn' : ''}${
            slamArmed && !warn ? ' om-preview-slam' : ''
          }`}
          transform={`translate(${preview.x} ${preview.y})`}
          data-testid="om-preview"
          aria-hidden="true"
        >
          <g className="om-in">
            <g className="om-preview-stone">
              <StoneBody id={id} color={myColor} />
            </g>
            <circle className="om-art om-preview-ring" r={0.58} />
          </g>
        </g>
      )}

      {warn && (
        <g key={`w${key(warn)}`} className="om-art" transform={`translate(${warn.x} ${warn.y})`} aria-hidden="true">
          <circle className="om-ink om-ink-1" cx={-0.04} cy={0.03} r={0.5} fill={`url(#${id}-ink)`} />
          <circle className="om-ink om-ink-2" cx={0.3} cy={-0.24} r={0.2} fill={`url(#${id}-ink)`} />
          <circle className="om-ink om-ink-3" cx={-0.32} cy={0.28} r={0.14} fill={`url(#${id}-ink)`} />
          <circle className="om-warn-ring" r={0.3} />
        </g>
      )}
    </svg>
  );
}
