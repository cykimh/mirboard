import { create } from 'zustand';
import type { Card, HandType } from '@/types/tichu';

export type EffectKind =
  | 'BOMB'
  | 'STRAIGHT_FLUSH_BOMB'
  | 'TICHU_DECLARED'
  | 'MATCH_VICTORY'
  | 'MY_TURN';

/** MATCH_VICTORY 연출 톤 — 본인 승/패/관전(중립)에 따라 다르게 렌더. */
export type EffectTone = 'win' | 'lose' | 'neutral';

export interface ActiveEffect {
  id: number;
  kind: EffectKind;
  /** TICHU_DECLARED 전용 — 배너에 표시할 문구 (예: "🔔 티츄! · 시트 2"). */
  text?: string;
  /** MATCH_VICTORY 전용 — 승/패/중립 톤. */
  tone?: EffectTone;
  /** TICHU_DECLARED 전용(D-241) — 그랜드 티츄면 true. 옻칠 테마의 인장이 문구를 고른다. */
  grand?: boolean;
  /** 자동 해제 epoch ms. */
  expiresAt: number;
}

/**
 * 경기장 연출(D-241) — 좌석·가운데에 붙는 짧은 연출. 화면 전역 배너(`active`)와 달리 여럿이 겹쳐 돈다
 * (트릭이 쓸려 가는 동안 다음 선언 인장이 찍힐 수 있다). 옻칠 테마의 `ArenaFxLayer` 가 그린다.
 */
export type ArenaFxKind =
  /** 딜 — 가운데에서 네 좌석으로 뒷면 카드가 날아간다. */
  | 'DEAL'
  /** 트릭 가져가기 — 가운데 카드 더미가 `seat` 쪽으로 쓸려 간다. `points` 는 그 트릭 점수. */
  | 'TRICK_SWEEP'
  /** 용 트릭 넘기기 — `fromSeat` 에서 `seat` 로 용 패가 건너간다. */
  | 'DRAGON_GIVEN'
  /** 티츄·그랜드 티츄 선언 — `seat` 에 붉은 인장 + 금빛 고리. */
  | 'DECLARE_STAMP'
  /** 소원(마작) — 가운데에 소원 숫자 패가 뜬다. */
  | 'WISH';

export interface ArenaFx {
  id: number;
  kind: ArenaFxKind;
  seat?: number;
  fromSeat?: number;
  cards?: Card[];
  points?: number;
  grand?: boolean;
  rank?: number;
  /** 자동 해제 epoch ms. */
  expiresAt: number;
}

export type ArenaFxInput = Omit<ArenaFx, 'id' | 'expiresAt'>;

interface EffectState {
  active: ActiveEffect | null;
  /** D-241 — 경기장 연출 줄. 넣을 때마다 만료분을 걷고 최대 {@link ARENA_FX_MAX}개만 둔다. */
  arenaFx: ArenaFx[];
  trigger: (kind: EffectKind, text?: string, tone?: EffectTone, grand?: boolean) => void;
  clear: () => void;
  pushArenaFx: (fx: ArenaFxInput) => void;
  /** 만료된 경기장 연출을 걷는다(연출 층의 타이머가 부른다). */
  pruneArenaFx: () => void;
  clearArenaFx: () => void;
}

/** 이펙트별 노출 시간. 선언 배너는 모두가 인지하도록 약간 더 길게. */
const DURATION_BY_KIND: Record<EffectKind, number> = {
  BOMB: 1800,
  STRAIGHT_FLUSH_BOMB: 1800,
  TICHU_DECLARED: 2000,
  // 매치 종료 승리 연출 — MVP 카드로 시선이 넘어갈 시간까지 약간 길게.
  MATCH_VICTORY: 3500,
  // 내 차례 — 매 차례라 짧고 비차단.
  MY_TURN: 1300,
};

/** 경기장 연출 노출 시간(ms) — CSS 애니메이션 길이(`25-tichu-lacquer.css`)보다 조금 길다. */
export const ARENA_FX_DURATION: Record<ArenaFxKind, number> = {
  DEAL: 1100,
  TRICK_SWEEP: 1250,
  DRAGON_GIVEN: 1300,
  DECLARE_STAMP: 1900,
  WISH: 1900,
};
/** 줄에 한꺼번에 둘 수 있는 연출 수 — 넘으면 오래된 것부터 버린다(연출 층이 안 떠 있어도 쌓이지 않게). */
export const ARENA_FX_MAX = 8;
let nextId = 1;

/**
 * Phase 8G — 하이핸드 이펙트 dispatcher. tichuStore.applyEvent 의 PLAYED 분기에서
 * handType in {BOMB, STRAIGHT_FLUSH_BOMB} 이면 trigger. EffectsOverlay 컴포넌트가
 * active 를 구독해 화면 플래시 + SVG 폭발 렌더.
 */
export const useEffectStore = create<EffectState>((set) => ({
  active: null,
  arenaFx: [],
  trigger: (kind, text, tone, grand) => {
    const id = nextId++;
    set({
      active: { id, kind, text, tone, grand, expiresAt: Date.now() + DURATION_BY_KIND[kind] },
    });
  },
  clear: () => set({ active: null }),
  pushArenaFx: (fx) => {
    const now = Date.now();
    const id = nextId++;
    set((s) => ({
      arenaFx: [
        ...s.arenaFx.filter((f) => f.expiresAt > now),
        { ...fx, id, expiresAt: now + ARENA_FX_DURATION[fx.kind] },
      ].slice(-ARENA_FX_MAX),
    }));
  },
  pruneArenaFx: () => {
    const now = Date.now();
    set((s) => {
      const kept = s.arenaFx.filter((f) => f.expiresAt > now);
      return kept.length === s.arenaFx.length ? s : { arenaFx: kept };
    });
  },
  clearArenaFx: () => set({ arenaFx: [] }),
}));

/** HandType 가 하이핸드면 EffectKind 반환, 아니면 null. */
export function effectForHandType(type: HandType): EffectKind | null {
  if (type === 'BOMB') return 'BOMB';
  if (type === 'STRAIGHT_FLUSH_BOMB') return 'STRAIGHT_FLUSH_BOMB';
  return null;
}
