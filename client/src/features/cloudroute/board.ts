import type { CloudRouteLetter } from '@/types/cloudroute';

/**
 * 구름항로 판 24칸 — 서버 `board/Board` 의 표시용 사본(이름·종류·섬·땅값·통행료). 판정은 서버가 하고, 여기 값은 칸 시트·결정 패널의
 * 글자로만 쓴다. 서버 표를 바꾸면 같이 고친다(`board.test` 가 룰 정본 §2·§6.3 의 대표값을 붙잡는다).
 */

export type TileKind = 'START' | 'LAND' | 'LETTER' | 'WHIRLPOOL' | 'CUSTOMS' | 'FESTIVAL' | 'STATION';
export type Island = 'MOSS' | 'SHELL' | 'MUSHROOM' | 'WINDMILL' | 'GLASS' | 'STAR_APPLE' | 'LAVA' | 'CROWN';

export interface TileInfo {
  id: number;
  name: string;
  kind: TileKind;
  island: Island | null;
  price: number;
  buildCost: number;
  /** [땅만, 천막, 상점, 풍등탑] */
  tolls: number[];
}

export const BOARD_SIZE = 24;
export const STATION = 18;

const land = (id: number, name: string, island: Island, price: number, tolls: number[]): TileInfo => ({
  id,
  name,
  kind: 'LAND',
  island,
  price,
  buildCost: price / 2,
  tolls,
});
const special = (id: number, name: string, kind: TileKind): TileInfo => ({
  id,
  name,
  kind,
  island: null,
  price: 0,
  buildCost: 0,
  tolls: [],
});

export const TILES: readonly TileInfo[] = [
  special(0, '출발 항구', 'START'),
  land(1, '이끼 나루', 'MOSS', 60, [10, 40, 80, 170]),
  land(2, '이끼 언덕', 'MOSS', 60, [10, 40, 80, 170]),
  special(3, '바람 편지', 'LETTER'),
  land(4, '조개 모래톱', 'SHELL', 80, [20, 50, 110, 220]),
  land(5, '조개 시장', 'SHELL', 80, [20, 50, 110, 220]),
  special(6, '소용돌이', 'WHIRLPOOL'),
  land(7, '버섯 숲길', 'MUSHROOM', 100, [20, 60, 140, 280]),
  land(8, '버섯 마을', 'MUSHROOM', 100, [20, 60, 140, 280]),
  special(9, '세관', 'CUSTOMS'),
  land(10, '풍차 들판', 'WINDMILL', 120, [20, 70, 170, 340]),
  land(11, '풍차 방앗간', 'WINDMILL', 120, [20, 70, 170, 340]),
  special(12, '등불 축제', 'FESTIVAL'),
  land(13, '유리 공방', 'GLASS', 150, [30, 90, 210, 420]),
  land(14, '유리 다리', 'GLASS', 150, [30, 90, 210, 420]),
  special(15, '바람 편지', 'LETTER'),
  land(16, '별사과 과수원', 'STAR_APPLE', 170, [30, 100, 240, 480]),
  land(17, '별사과 장터', 'STAR_APPLE', 170, [30, 100, 240, 480]),
  special(18, '바람 정류장', 'STATION'),
  land(19, '용암 온천', 'LAVA', 200, [40, 120, 280, 560]),
  land(20, '용암 대장간', 'LAVA', 200, [40, 120, 280, 560]),
  special(21, '바람 편지', 'LETTER'),
  land(22, '구름왕관 정원', 'CROWN', 240, [50, 140, 340, 670]),
  land(23, '구름왕관 성채', 'CROWN', 240, [50, 140, 340, 670]),
];

export const ISLAND_NAME: Record<Island, string> = {
  MOSS: '이끼섬',
  SHELL: '조개섬',
  MUSHROOM: '버섯섬',
  WINDMILL: '풍차섬',
  GLASS: '유리섬',
  STAR_APPLE: '별사과섬',
  LAVA: '용암섬',
  CROWN: '구름왕관섬',
};

export const LEVEL_NAME = ['땅만', '천막', '상점', '풍등탑'] as const;

export const KIND_HINT: Record<Exclude<TileKind, 'LAND'>, string> = {
  START: '지나거나 멈추면 급여 200G',
  LETTER: '바람 편지 한 장을 뽑는다',
  WHIRLPOOL: '멈추면 갇힌다 — 더블·50G·구명 부표로 나온다',
  CUSTOMS: '100G 를 낸다',
  FESTIVAL: '내 땅 하나에 축제(통행료 ×2)',
  STATION: '50G 내고 아무 칸으로 날아간다',
};

export const LETTER_TEXT: Record<CloudRouteLetter, { name: string; effect: string }> = {
  TAILWIND: { name: '순풍', effect: '출발 항구로 날아가 급여 200G' },
  SAIL_REPAIR: { name: '돛 수선', effect: '80G 를 낸다' },
  STAR_CHEST: { name: '별똥별 상자', effect: '120G 를 받는다' },
  HEADWIND: { name: '맞바람', effect: '3칸 뒤로' },
  WHIRLPOOL_ALERT: { name: '소용돌이 경보', effect: '소용돌이로 곧장' },
  BUOY: { name: '구명 부표', effect: '소용돌이에서 한 번 바로 나온다' },
  INSPECTION: { name: '건물 점검', effect: '건물 단계마다 20G' },
  ISLAND_FEAST: { name: '섬 잔치 초대', effect: '다른 사람마다 30G 씩 받는다' },
  WIND_SHARE: { name: '바람 나눔', effect: '가장 부자가 가장 가난한 사람에게 100G' },
  GUST: { name: '등 떠미는 바람', effect: '2칸 앞으로' },
};

export function partnerOf(tile: number): number {
  const info = TILES[tile];
  return TILES.find((t) => t.id !== tile && t.island !== null && t.island === info.island)?.id ?? -1;
}

/**
 * 칸 → 7×7 격자 좌표(행·열, 0 부터). 출발 항구가 아래 오른쪽 모서리이고 진행은 화면에서 시계 방향 — 아래 줄 오른쪽→왼쪽,
 * 왼쪽 줄 아래→위, 윗줄 왼쪽→오른쪽, 오른쪽 줄 위→아래(설계서 §3.2 그림). 격자 좌표를 아는 곳은 여기뿐이다.
 */
export function gridOf(tile: number): { row: number; col: number } {
  if (tile <= 6) return { row: 6, col: 6 - tile };
  if (tile <= 12) return { row: 12 - tile, col: 0 };
  if (tile <= 18) return { row: 0, col: tile - 12 };
  return { row: tile - 18, col: 6 };
}
