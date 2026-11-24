import { useEffect, useRef, useState, type CSSProperties } from 'react';
import {
  BOARD_SIZE,
  FACE_COUNT,
  type DambadukCell,
  type DambadukDir,
  type DambadukMoveOption,
  type DambadukStone,
  type DambadukStoneKind,
  type DambadukTerritory,
} from '@/types/dambaduk';
import { COLOR_KEY, COLOR_NAME } from './dambadukTheme';
import { DIR_LABEL, cellIndex, cellName, faceOf, segmentOf } from './dambadukGeometry';
import { StoneArt, WallArt, WallImpactArt } from './dambadukArt';

/** 판 그림 좌표 — 칸 하나가 100. */
const U = 100;
const SIZE = BOARD_SIZE * U;

export const KIND_NAME: Record<DambadukStoneKind, string> = { STRAIGHT: '곧은돌', BENT: '꺾은돌' };

/** 도착 칸 하나와 그 칸으로 가는 수들. */
export interface Destination {
  cell: DambadukCell;
  options: DambadukMoveOption[];
}

export interface DambadukBoardProps {
  stones: DambadukStone[];
  walls: number[];
  territory: DambadukTerritory;
  seatColors: number[];
  /** 내 좌석(관전자·튜토리얼은 −1). */
  mySeat?: number;
  /** 지금 누를 수 있는 것 — 배치: 빈 칸 / 옮김: 내 돌·도착 칸·담 / 없음. */
  mode?: 'none' | 'place' | 'move';
  /** 배치 — 고른 칸과 종류(흐린 돌). */
  placeCell?: DambadukCell | null;
  placeKind?: DambadukStoneKind | null;
  /** 옮김 — 고를 수 있는 내 돌. */
  movableStoneIds?: ReadonlySet<number>;
  selectedStoneId?: number | null;
  destinations?: Destination[];
  /** 고른 도착 — 그 칸의 담 자리를 보인다. */
  choice?: DambadukMoveOption | null;
  /** 보낸 수(응답 전) — 흐리게. */
  pendingPlace?: { x: number; y: number; kind: DambadukStoneKind; seat: number } | null;
  pendingMove?: { stoneId: number; toX: number; toY: number; wallFace: number } | null;
  /** 연출 — 마지막 옮김의 담·경로, 바뀔 때마다 오르는 열쇠. */
  lastWallFace?: number;
  lastTrail?: { from: DambadukCell; to: DambadukCell; jumpedFace: number } | null;
  fxSeq?: number;
  onCell?: (cell: DambadukCell) => void;
  onWall?: (dir: DambadukDir) => void;
  /** 튜토리얼 그림처럼 작고 누를 수 없는 판. */
  mini?: boolean;
  /** 그림에 붙일 이름(접근성). */
  label?: string;
  /** 튜토리얼 그림 — 이 칸들에 표시(갈 수 있는 칸 등). */
  marks?: DambadukCell[];
}

function stoneLabel(st: DambadukStone, seatColors: number[]): string {
  return `${COLOR_NAME[seatColors[st.seat] ?? 0] ?? ''} ${KIND_NAME[st.kind]}`;
}

/** 집 칸 → 주인 좌석. */
function homeOwners(territory: DambadukTerritory): Map<number, number> {
  const out = new Map<number, number>();
  for (const r of territory.regions) {
    if (r.kind === 'HOME') for (const c of r.cells) out.set(c, r.owner);
  }
  return out;
}

/** 새로 생긴 집 둘레의 고리 하나(D-307) — 주인 좌석과 판 그림 좌표의 네모. */
interface HomeRing {
  owner: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 방금 새로 집이 된 칸들을 주인별로 묶어 둘레 네모로. 판정이 아니다 — 서버가 보낸 터의 앞뒤만 견준다. */
function newHomeRings(prev: Map<number, number>, now: Map<number, number>): HomeRing[] {
  const box = new Map<number, { x0: number; y0: number; x1: number; y1: number }>();
  for (const [idx, owner] of now) {
    if (prev.get(idx) === owner) continue;
    const x = idx % BOARD_SIZE;
    const y = Math.floor(idx / BOARD_SIZE);
    const b = box.get(owner);
    if (!b) box.set(owner, { x0: x, y0: y, x1: x, y1: y });
    else {
      b.x0 = Math.min(b.x0, x);
      b.y0 = Math.min(b.y0, y);
      b.x1 = Math.max(b.x1, x);
      b.y1 = Math.max(b.y1, y);
    }
  }
  return Array.from(box.entries()).map(([owner, b]) => ({
    owner,
    x: b.x0 * U + 6,
    y: b.y0 * U + 6,
    width: (b.x1 - b.x0 + 1) * U - 12,
    height: (b.y1 - b.y0 + 1) * U - 12,
  }));
}

const cellPos = (c: DambadukCell): CSSProperties =>
  ({ '--db-x': c.x, '--db-y': c.y }) as CSSProperties;

/**
 * 담바둑 판 (D-279). 세 겹: 그림(`svg.db-board-art` — 나무판·먹선·집 번짐·담), 돌(`.db-stones` — 좌표 CSS 변수라 옮기면 미끄러진다),
 * 누르는 칸(`.db-cells` — 49개 버튼, 이름이 곧 칸 이름·내용)과 담 고르기 손잡이. 판정은 하지 않는다 — 갈 수 있는 칸·담 자리·집은
 * 서버가 보낸 값을 그린다.
 */
export function DambadukBoard({
  stones,
  walls,
  territory,
  seatColors,
  mySeat = -1,
  mode = 'none',
  placeCell = null,
  placeKind = null,
  movableStoneIds,
  selectedStoneId = null,
  destinations = [],
  choice = null,
  pendingPlace = null,
  pendingMove = null,
  lastWallFace = -1,
  lastTrail = null,
  fxSeq = 0,
  onCell,
  onWall,
  mini = false,
  label,
  marks = [],
}: DambadukBoardProps) {
  const wallSet = new Set(walls);
  const owners = homeOwners(territory);
  const stoneAt = new Map<number, DambadukStone>();
  for (const st of stones) stoneAt.set(cellIndex(st), st);
  const destAt = new Map<number, Destination>();
  for (const d of destinations) destAt.set(cellIndex(d.cell), d);
  const markSet = new Set(marks.map(cellIndex));
  const choiceCell = choice ? { x: choice.toX, y: choice.toY } : null;
  const candidateFaces = choice && choiceCell ? choice.walls.map((d) => ({ dir: d, face: faceOf(choiceCell, d) })) : [];
  const interactive = !mini && mode !== 'none';

  const faces = Array.from({ length: FACE_COUNT }, (_, f) => f);

  // D-307 — 큰 연출(담이 닿는 순간의 빛·돌가루·판 흔들림, 지운 담, 새 집 고리)은 이 화면이 **받은 수**에만 돈다. 처음 그릴 때의
  // `fxSeq` 를 기억해 두고, 판을 다시 그린 것(재입장·튜토리얼 작은 판)에는 돌지 않는다.
  const mountFx = useRef(fxSeq);
  const live = !mini && fxSeq !== mountFx.current;
  const impactFace = live && lastWallFace >= 0 && wallSet.has(lastWallFace) ? lastWallFace : -1;
  const goneFace = live && lastTrail && lastTrail.jumpedFace >= 0 && !wallSet.has(lastTrail.jumpedFace) ? lastTrail.jumpedFace : -1;
  // 새 집 고리 — 터는 옮김 뒤의 TURN_CHANGED 가 싣는다. 앞선 터와 견줘 새로 집이 된 칸이 있을 때만 한 번.
  const prevHomes = useRef<Map<number, number> | null>(null);
  const [homeFx, setHomeFx] = useState<{ key: number; rings: HomeRing[] } | null>(null);
  useEffect(() => {
    const now = homeOwners(territory);
    const prev = prevHomes.current;
    prevHomes.current = now;
    if (!prev || mini) return;
    const rings = newHomeRings(prev, now);
    if (rings.length > 0) setHomeFx((was) => ({ key: (was?.key ?? 0) + 1, rings }));
  }, [territory, mini]);

  return (
    <div
      className={`db-board${mini ? ' db-board-mini' : ''}${interactive ? ' db-board-live' : ''}${
        impactFace >= 0 ? ` db-board-thud-${fxSeq % 2 === 0 ? 'a' : 'b'}` : ''
      }`}
      role={mini ? 'img' : undefined}
      aria-label={mini ? label : undefined}
      data-fx={fxSeq}
    >
      <svg className="db-board-art" viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true" focusable="false">
        <rect className="db-wood" x="0" y="0" width={SIZE} height={SIZE} rx="18" />
        {/* 집 — 그 사람 색이 먹물처럼 번진다. */}
        {Array.from(owners.entries()).map(([idx, owner]) => {
          const x = (idx % BOARD_SIZE) * U;
          const y = Math.floor(idx / BOARD_SIZE) * U;
          return (
            <rect
              key={`h${idx}`}
              className={`db-home db-c-${COLOR_KEY[seatColors[owner] ?? 0]}`}
              x={x}
              y={y}
              width={U}
              height={U}
            />
          );
        })}
        {/* 먹선 — 담을 세울 수 있는 빈 면. */}
        {faces
          .filter((f) => !wallSet.has(f))
          .map((f) => {
            const sg = segmentOf(f);
            return (
              <line key={`l${f}`} className="db-line" x1={sg.x1 * U} y1={sg.y1 * U} x2={sg.x2 * U} y2={sg.y2 * U} />
            );
          })}
        {/* 지난 수의 발자국 — 출발에서 도착까지. */}
        {lastTrail && (
          <line
            key={`t${fxSeq}`}
            className="db-trail"
            x1={(lastTrail.from.x + 0.5) * U}
            y1={(lastTrail.from.y + 0.5) * U}
            x2={(lastTrail.to.x + 0.5) * U}
            y2={(lastTrail.to.y + 0.5) * U}
          />
        )}
        {/* 고른 도착의 담 자리 — 점선. */}
        {candidateFaces.map(({ dir, face }) => {
          const sg = segmentOf(face);
          return (
            <line
              key={`c${dir}`}
              className="db-wall-candidate"
              x1={sg.x1 * U}
              y1={sg.y1 * U}
              x2={sg.x2 * U}
              y2={sg.y2 * U}
            />
          );
        })}
        {/* D-307 — 새 담이 닿는 자리의 빛·먹 번짐·돌가루. 담 아래에 깐다(빛무리가 담을 가리지 않게). */}
        {impactFace >= 0 && <WallImpactArt key={`i${fxSeq}`} {...scaled(segmentOf(impactFace))} />}
        {/* 담 — 돌담 결. 방금 세운 담은 위에서 떨어져 닿는 연출. */}
        {walls.map((f) => {
          const sg = segmentOf(f);
          return (
            <WallArt
              key={f === lastWallFace ? `w${f}-${fxSeq}` : `w${f}`}
              x1={sg.x1 * U}
              y1={sg.y1 * U}
              x2={sg.x2 * U}
              y2={sg.y2 * U}
              fresh={f === lastWallFace}
            />
          );
        })}
        {pendingMove && pendingMove.wallFace >= 0 && !wallSet.has(pendingMove.wallFace) && (
          <WallArt {...scaled(segmentOf(pendingMove.wallFace))} pending />
        )}
        {/* D-307 — 담 넘기로 지운 담이 흩어지고, 새 집 둘레에 주인 색 고리가 퍼진다. */}
        {goneFace >= 0 && <WallArt key={`g${fxSeq}`} {...scaled(segmentOf(goneFace))} gone />}
        {homeFx?.rings.map((r) => (
          <rect
            key={`r${homeFx.key}-${r.owner}`}
            className={`db-home-ring db-c-${COLOR_KEY[seatColors[r.owner] ?? 0]}`}
            x={r.x}
            y={r.y}
            width={r.width}
            height={r.height}
            rx="14"
          />
        ))}
        <rect className="db-frame" x="4" y="4" width={SIZE - 8} height={SIZE - 8} rx="16" />
        {/* 바깥담 장식 — 테 안쪽의 가는 그늘(테마 층에서만 보인다). 돌 테는 CSS(`.db-board::before`)가 판 밖에 두른다. */}
        <rect className="db-frame-lip" x="9.5" y="9.5" width={SIZE - 19} height={SIZE - 19} rx="11" />
      </svg>

      <div className="db-stones" aria-hidden="true">
        {stones.map((st) => {
          const moving = pendingMove?.stoneId === st.id;
          const at = moving ? { x: pendingMove!.toX, y: pendingMove!.toY } : st;
          return (
            <span
              key={st.id}
              className={`db-stone db-kind-${st.kind === 'STRAIGHT' ? 'straight' : 'bent'} db-c-${
                COLOR_KEY[seatColors[st.seat] ?? 0]
              }${st.seat === mySeat ? ' db-stone-mine' : ''}${st.id === selectedStoneId ? ' db-stone-selected' : ''}${
                movableStoneIds?.has(st.id) && selectedStoneId === null ? ' db-stone-movable' : ''
              }${moving ? ' db-stone-pending' : ''}`}
              style={cellPos(at)}
              data-stone-id={st.id}
            >
              <StoneArt kind={st.kind} />
            </span>
          );
        })}
        {pendingPlace && (
          <span
            className={`db-stone db-stone-pending db-kind-${pendingPlace.kind === 'STRAIGHT' ? 'straight' : 'bent'} db-c-${
              COLOR_KEY[seatColors[pendingPlace.seat] ?? 0]
            }`}
            style={cellPos(pendingPlace)}
          >
            <StoneArt kind={pendingPlace.kind} />
          </span>
        )}
        {placeCell && placeKind && !stoneAt.has(cellIndex(placeCell)) && (
          <span
            className={`db-stone db-stone-ghost db-kind-${placeKind === 'STRAIGHT' ? 'straight' : 'bent'} db-c-${
              COLOR_KEY[seatColors[mySeat] ?? 0]
            }`}
            style={cellPos(placeCell)}
            data-testid="db-place-ghost"
          >
            <StoneArt kind={placeKind} />
          </span>
        )}
        {choice && selectedStoneId !== null && (
          <span
            className={`db-stone db-stone-ghost db-kind-${
              stones.find((st) => st.id === selectedStoneId)?.kind === 'BENT' ? 'bent' : 'straight'
            } db-c-${COLOR_KEY[seatColors[mySeat] ?? 0]}`}
            style={cellPos({ x: choice.toX, y: choice.toY })}
            data-testid="db-move-ghost"
          >
            <StoneArt kind={stones.find((st) => st.id === selectedStoneId)?.kind ?? 'STRAIGHT'} />
          </span>
        )}
        {Array.from(destAt.values()).map((d) => (
          <span
            key={`d${cellIndex(d.cell)}`}
            className={`db-dest${d.options.some((o) => o.jump) ? ' db-dest-jump' : ''}${
              choiceCell && choiceCell.x === d.cell.x && choiceCell.y === d.cell.y ? ' db-dest-chosen' : ''
            }`}
            style={cellPos(d.cell)}
          />
        ))}
        {marks.map((c) => (
          <span key={`m${cellIndex(c)}`} className="db-dest db-mark" style={cellPos(c)} />
        ))}
      </div>

      {!mini && (
        <div className="db-cells">
          {Array.from({ length: BOARD_SIZE * BOARD_SIZE }, (_, idx) => {
            const cell = { x: idx % BOARD_SIZE, y: Math.floor(idx / BOARD_SIZE) };
            const st = stoneAt.get(idx);
            const dest = destAt.get(idx);
            let enabled = false;
            let what: string;
            if (st) {
              what = `${st.seat === mySeat ? '내 ' : ''}${stoneLabel(st, seatColors)}${
                st.id === selectedStoneId ? ' (고름)' : movableStoneIds?.has(st.id) ? ' — 옮길 수 있음' : ''
              }`;
              enabled = mode === 'move' && (movableStoneIds?.has(st.id) ?? false);
            } else if (dest) {
              what = dest.options.some((o) => o.jump) ? '담 넘어 갈 수 있는 칸' : '갈 수 있는 칸';
              enabled = mode === 'move';
            } else {
              what = owners.has(idx) ? `${COLOR_NAME[seatColors[owners.get(idx)!] ?? 0]} 집` : '빈칸';
              enabled = mode === 'place' || (mode === 'move' && selectedStoneId !== null);
            }
            if (markSet.has(idx)) what += ' — 표시';
            return (
              <button
                key={idx}
                type="button"
                className={`db-cell${enabled ? ' db-cell-on' : ''}`}
                style={cellPos(cell)}
                aria-label={`${cellName(cell)} ${what}`}
                aria-disabled={enabled ? undefined : true}
                tabIndex={enabled ? 0 : -1}
                onClick={() => {
                  if (enabled) onCell?.(cell);
                }}
              />
            );
          })}
          {choice &&
            choiceCell &&
            choice.walls.map((d) => (
              <button
                key={`p${d}`}
                type="button"
                className={`db-wall-pick db-wall-pick-${d.toLowerCase()}`}
                style={cellPos(choiceCell)}
                aria-label={`${cellName(choiceCell)} ${DIR_LABEL[d]}에 담 세우기`}
                onClick={() => onWall?.(d)}
              />
            ))}
        </div>
      )}
    </div>
  );
}

function scaled(sg: { x1: number; y1: number; x2: number; y2: number }) {
  return { x1: sg.x1 * U, y1: sg.y1 * U, x2: sg.x2 * U, y2: sg.y2 * U };
}
