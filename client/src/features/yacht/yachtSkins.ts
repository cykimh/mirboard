import { create } from 'zustand';

/**
 * D-296 — 얏추 주사위·경기판 스킨. <b>내 화면만</b> 바뀐다: 고른 값은 이 브라우저의 `localStorage` 에만 있고 서버로 가지 않는다.
 * 살롱 테마 층(D-203) 위에 얹는다 — 경기판은 게임판 루트의 `yt-board-*` 클래스(판·레일 토큰과 무늬 한 겹, `20-yacht-table.css` 의
 * "스킨" 구역), 주사위는 `YachtDie` 가 아래 색표로 같은 SVG 를 칠한다. 그림은 전부 인라인 SVG·CSS 그라데이션이다(외부 에셋 없음).
 */
export const DICE_SKINS = ['ivory', 'amber', 'jade', 'obsidian', 'walnut'] as const;
export type DiceSkin = (typeof DICE_SKINS)[number];
export const BOARD_SKINS = ['felt', 'ocean', 'night', 'tartan', 'wood'] as const;
export type BoardSkin = (typeof BOARD_SKINS)[number];

export const DEFAULT_DICE_SKIN: DiceSkin = 'ivory';
export const DEFAULT_BOARD_SKIN: BoardSkin = 'felt';

export const DICE_SKIN_LABEL: Record<DiceSkin, string> = {
  ivory: '상아',
  amber: '호박',
  jade: '옥',
  obsidian: '흑요석',
  walnut: '호두나무',
};

export const BOARD_SKIN_LABEL: Record<BoardSkin, string> = {
  felt: '초록 펠트',
  ocean: '바다',
  night: '밤하늘',
  tartan: '타탄 체크',
  wood: '나무 판',
};

/** 주사위 한 벌의 색. 몸통·눈은 왼쪽 위(밝음) → 오른쪽 아래(어두움) 세 단계 그라데이션이다. */
export interface DiePalette {
  body: readonly [string, string, string];
  stroke: string;
  /** 아래쪽으로 깔리는 그늘 색. */
  shade: string;
  /** 위 모서리를 따라 도는 빛줄. */
  shine: string;
  shineOpacity: number;
  pip: readonly [string, string, string];
  /** 파인 눈의 아래 가장자리에 비치는 테. */
  rim: string;
  rimOpacity: number;
  /** 작은 주사위(좌석 띠·점수표·정육면체 옆면) — 그라데이션 없이 단색. */
  mini: string;
  miniStroke: string;
  miniPip: string;
  /** 나뭇결(있으면 큰 주사위에 옅은 결 세 줄). */
  grain?: string;
}

/**
 * 눈은 색이 아니라 <b>밝기 차이</b>로 읽힌다 — 어느 스킨이든 눈과 몸통의 명암비가 4.5 이상(작은 주사위 7 이상)이다
 * (`yachtSkins.test` 가 계산한다. 색약에서도 같다). 어두운 주사위(흑요석·호두나무)는 눈이 밝다.
 */
export const DICE_PALETTES: Record<DiceSkin, DiePalette> = {
  ivory: {
    body: ['#fffaf0', '#f3e9d2', '#d9caa6'],
    stroke: '#a89972',
    shade: '#6b5a2e',
    shine: '#ffffff',
    shineOpacity: 0.75,
    pip: ['#4a4a4a', '#17181a', '#000000'],
    rim: '#ffffff',
    rimOpacity: 0.7,
    mini: '#f7efdc',
    miniStroke: '#a89972',
    miniPip: '#1b1c1f',
  },
  amber: {
    body: ['#ffe9ae', '#f7bd55', '#d08a22'],
    stroke: '#8a5a12',
    shade: '#5a3206',
    shine: '#fff8dc',
    shineOpacity: 0.8,
    pip: ['#4a2c10', '#1d0e02', '#0a0400'],
    rim: '#ffe9ae',
    rimOpacity: 0.65,
    mini: '#f7bd55',
    miniStroke: '#8a5a12',
    miniPip: '#1d0e02',
  },
  jade: {
    body: ['#f1fff5', '#bfe6cd', '#86c2a0'],
    stroke: '#4f8a6c',
    shade: '#1f4a35',
    shine: '#ffffff',
    shineOpacity: 0.8,
    pip: ['#2c4637', '#0c1f16', '#020a06'],
    rim: '#f1fff5',
    rimOpacity: 0.7,
    mini: '#c9ecd6',
    miniStroke: '#4f8a6c',
    miniPip: '#0c1f16',
  },
  obsidian: {
    body: ['#565c69', '#262a32', '#0d0e12'],
    stroke: '#8d96a8',
    shade: '#000000',
    shine: '#cfd8ea',
    shineOpacity: 0.45,
    pip: ['#ffffff', '#f4f1e6', '#cfcabb'],
    rim: '#000000',
    rimOpacity: 0.55,
    mini: '#23262d',
    miniStroke: '#8d96a8',
    miniPip: '#f7f4ea',
  },
  walnut: {
    body: ['#8f6238', '#74491f', '#4a2c10'],
    stroke: '#2e1a08',
    shade: '#1a0d02',
    shine: '#ffe2b8',
    shineOpacity: 0.4,
    pip: ['#ffffff', '#fff3d6', '#e6d3a8'],
    rim: '#1a0d02',
    rimOpacity: 0.5,
    mini: '#74491f',
    miniStroke: '#2e1a08',
    miniPip: '#fff6e0',
    grain: '#2e1a08',
  },
};

export const DICE_SKIN_KEY = 'mirboard.yacht.skin.dice';
export const BOARD_SKIN_KEY = 'mirboard.yacht.skin.board';

/** 저장값을 읽는다 — 저장 불가·모르는 값(지운 스킨·손댄 값)이면 기본값. */
export function loadSkin<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw !== null && (allowed as readonly string[]).includes(raw) ? (raw as T) : fallback;
  } catch {
    return fallback;
  }
}

function save(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 저장 불가(사생활 보호 모드 등) — 이번 화면에서만 적용된다.
  }
}

interface YachtSkinState {
  dice: DiceSkin;
  board: BoardSkin;
  setDice: (skin: DiceSkin) => void;
  setBoard: (skin: BoardSkin) => void;
}

export const useYachtSkinStore = create<YachtSkinState>((set) => ({
  dice: loadSkin(DICE_SKIN_KEY, DICE_SKINS, DEFAULT_DICE_SKIN),
  board: loadSkin(BOARD_SKIN_KEY, BOARD_SKINS, DEFAULT_BOARD_SKIN),
  setDice: (dice) => {
    if (!DICE_SKINS.includes(dice)) return;
    save(DICE_SKIN_KEY, dice);
    set({ dice });
  },
  setBoard: (board) => {
    if (!BOARD_SKINS.includes(board)) return;
    save(BOARD_SKIN_KEY, board);
    set({ board });
  },
}));

/** 게임판 루트·판 견본에 붙일 클래스. */
export const boardSkinClass = (skin: BoardSkin) => `yt-board-${skin}`;
