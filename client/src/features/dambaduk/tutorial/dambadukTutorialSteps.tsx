import type { TutorialStep } from '@/features/tutorial/types';
import type { DambadukDir, DambadukRegion, DambadukStone, DambadukStoneKind, DambadukTerritory } from '@/types/dambaduk';
import { DambadukBoard } from '../DambadukBoard';
import { faceOf } from '../dambadukGeometry';

/**
 * 담바둑 튜토리얼 — 콘텐츠 출처 `docs/rules-dambaduk.md`(요약). 룰을 바꾸면 같은 커밋으로 여기를 고친다 — `dambadukTutorialSteps.test`
 * 가 핵심 문구를 붙잡는다. 판 그림은 게임판과 같은 판 컴포넌트의 작은 그림이다(그림 속 집은 손으로 적은 예 — 클라가 판정하지 않는다).
 */
const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;
const NO_TERRITORY: DambadukTerritory = { regions: [], scores: [], largest: [] };
const COLORS = [0, 1, 2];

/** "D4" → 칸. */
function at(label: string): { x: number; y: number } {
  return { x: label.charCodeAt(0) - 65, y: Number(label.slice(1)) - 1 };
}

function stone(id: number, seat: number, kind: DambadukStoneKind, label: string): DambadukStone {
  return { id, seat, kind, ...at(label) };
}

function wall(label: string, dir: DambadukDir): number {
  return faceOf(at(label), dir);
}

/** 칸 묶음(가로 x0..x1, 세로 y0..y1, 0부터) → 칸 번호. */
function block(x0: number, x1: number, y0: number, y1: number): number[] {
  const out: number[] = [];
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) out.push(y * 7 + x);
  return out;
}

// 룰 §12.3 — 왼쪽 21칸 흑 집, 오른쪽 위 16칸 백 집, 오른쪽 아래 12칸 빈 터.
const EXAMPLE_WALLS = [
  ...[1, 2, 3, 4, 5, 6, 7].map((row) => wall(`C${row}`, 'RIGHT')),
  ...['D', 'E', 'F', 'G'].map((col) => wall(`${col}4`, 'DOWN')),
];
const EXAMPLE_REGIONS: DambadukRegion[] = [
  { cells: block(0, 2, 0, 6), kind: 'HOME', owner: 0, seats: [0] },
  { cells: block(3, 6, 0, 3), kind: 'HOME', owner: 1, seats: [1] },
  { cells: block(3, 6, 4, 6), kind: 'EMPTY', owner: -1, seats: [] },
];

export const DAMBADUK_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '담바둑에 오신 걸 환영합니다',
    body: (
      <>
        <p>
          7×7 나무판에서 내 돌을 옮기고 <strong>담을 하나씩 쌓아</strong>, 내 돌만 있는 땅(<strong>집</strong>)을 가장 넓게
          두르는 게임입니다. 2~3명이 합니다.
        </p>
        <DambadukBoard
          mini
          label="2인 판의 시작 — 꺾은돌 넷이 정해진 칸에 있다"
          stones={[stone(0, 0, 'BENT', 'B2'), stone(1, 0, 'BENT', 'F6'), stone(2, 1, 'BENT', 'F2'), stone(3, 1, 'BENT', 'B6')]}
          walls={[]}
          territory={NO_TERRITORY}
          seatColors={COLORS}
        />
      </>
    ),
    source: '§1·§2',
  },
  {
    title: '두 가지 돌',
    body: (
      <>
        <ul style={LIST}>
          <li>
            <strong>곧은돌</strong>(둥근 돌) — 한 방향으로 1칸 또는 2칸.
          </li>
          <li>
            <strong>꺾은돌</strong>(모난 돌) — 1칸, 또는 1칸 간 뒤 왼쪽이나 오른쪽으로 꺾어 1칸(곧게 2칸은 못 간다).
          </li>
          <li>제자리에 있을 수 없고, 다른 돌이나 담을 지나갈 수 없습니다.</li>
        </ul>
        <div className="db-tut-pair">
          <DambadukBoard
            mini
            label="곧은돌이 갈 수 있는 칸"
            stones={[stone(0, 0, 'STRAIGHT', 'D4'), stone(1, 1, 'STRAIGHT', 'D2')]}
            walls={[wall('D4', 'RIGHT')]}
            territory={NO_TERRITORY}
            seatColors={COLORS}
            marks={['D3', 'B4', 'C4', 'D5', 'D6'].map(at)}
          />
          <DambadukBoard
            mini
            label="꺾은돌이 갈 수 있는 칸"
            stones={[stone(0, 0, 'BENT', 'D4'), stone(1, 1, 'STRAIGHT', 'E5')]}
            walls={[wall('D4', 'UP')]}
            territory={NO_TERRITORY}
            seatColors={COLORS}
            marks={['C3', 'E3', 'C4', 'E4', 'C5', 'D5'].map(at)}
          />
        </div>
      </>
    ),
    source: '§4.1·§4.2·§12.1·§12.2',
  },
  {
    title: '돌 놓기',
    body: (
      <ul style={LIST}>
        <li>먼저 할 사람(흑)은 매 판 무작위로 정해지고, 그다음 자리부터 백·청 순서입니다.</li>
        <li>
          <strong>2인</strong> — 한 사람이 4개: 꺾은돌 둘은 정해진 칸에서 시작하고, 곧은돌 둘을 흑 → 백 → 백 → 흑 순서로 빈 칸에
          놓습니다.
        </li>
        <li>
          <strong>3인</strong> — 한 사람이 2개(곧은돌·꺾은돌 하나씩): 흑 → 백 → 청 → 청 → 백 → 흑 순서로, 놓을 때 종류를 고릅니다.
        </li>
        <li>빈 칸을 누르면 흐린 돌이 보입니다 — 한 번 더 누르거나 [여기에 놓기]로 놓습니다. 다 놓으면 흑부터 옮깁니다.</li>
      </ul>
    ),
    source: '§2·§3',
  },
  {
    title: '한 차례 — 옮기고 담 세우기',
    body: (
      <>
        <p>
          내 돌 하나를 옮긴 뒤, 그 돌이 선 칸의 네 면 중 빈 면 하나에 <strong>반드시 담</strong>을 세웁니다. 들어온 면에 세워도
          됩니다. 판 가장자리는 처음부터 담입니다.
        </p>
        <ul style={LIST}>
          <li>내 돌 → 갈 수 있는 칸 → 담을 세울 면 순서로 누릅니다. 면을 누르는 순간 차례가 끝납니다.</li>
          <li>옮길 수 있는 돌이 하나도 없으면 그 차례는 저절로 건너뜁니다.</li>
        </ul>
      </>
    ),
    source: '§4·§4.3·§4.6',
  },
  {
    title: '집과 점수',
    body: (
      <>
        <p>
          담으로 나뉜 땅(<strong>터</strong>)에 내 돌만 있으면 그 터는 <strong>내 집</strong>입니다. 여러 사람 돌이 섞인 터와 돌이
          없는 터는 아무의 것도 아닙니다. 점수는 내 집의 칸 수를 모두 더한 값입니다.
        </p>
        <DambadukBoard
          mini
          label="끝난 판 — 흑 집 21칸, 백 집 16칸, 빈 터 12칸"
          stones={[
            stone(0, 0, 'STRAIGHT', 'B1'),
            stone(1, 0, 'STRAIGHT', 'A3'),
            stone(2, 0, 'BENT', 'C4'),
            stone(3, 0, 'BENT', 'B6'),
            stone(4, 1, 'STRAIGHT', 'F1'),
            stone(5, 1, 'STRAIGHT', 'D2'),
            stone(6, 1, 'BENT', 'G3'),
            stone(7, 1, 'BENT', 'E4'),
          ]}
          walls={EXAMPLE_WALLS}
          territory={{ regions: EXAMPLE_REGIONS, scores: [21, 16], largest: [21, 16] }}
          seatColors={COLORS}
        />
        <p>흑 21 : 백 16 — 흑 승. 점수가 같으면 가장 큰 집 하나가 더 큰 쪽이 이기고, 그것도 같으면 무승부입니다.</p>
        <p>내 집 안에 담을 세워 돌이 없는 부분을 떼어 내면 그 부분은 빈 터가 되어 점수가 줄어듭니다.</p>
      </>
    ),
    source: '§5·§7·§12.3',
  },
  {
    title: '판의 끝',
    body: (
      <ul style={LIST}>
        <li>
          <strong>섞인 터가 하나도 없어지면</strong> 그 자리에서 끝납니다.
        </li>
        <li>섞인 터가 남아도, 그 안의 어떤 돌도 더 옮길 수 없으면 끝납니다(더 바뀔 수 없으니까요).</li>
        <li>
          시간이 다 되면 서버가 대신 한 수 둡니다. 같은 사람이 <strong>두 번 연속</strong> 시간을 넘기면 그 사람이 빠집니다.
        </li>
        <li>3인 판에서 한 사람이 나가면 그 돌을 치우고 남은 사람끼리 계속합니다.</li>
      </ul>
    ),
    source: '§6·§8·§9',
  },
  {
    title: '3인 — 담 넘기',
    body: (
      <ul style={LIST}>
        <li>
          3인으로 시작한 판에서는 사람마다 <strong>한 판에 한 번</strong>, 가는 길의 담 하나를 넘어갈 수 있습니다. 넘은 담은
          없어집니다.
        </li>
        <li>없어진 자리에 같은 차례에 다시 담을 세워도 됩니다(담을 뛰어넘은 셈).</li>
        <li>[담 넘기]를 켜면 담 너머로 갈 수 있는 칸이 보입니다.</li>
      </ul>
    ),
    source: '§4.4',
  },
];
