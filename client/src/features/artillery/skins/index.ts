import type { ArtillerySkin } from './types';
import { createClaySkin } from './claySkin';
import { loadSpriteSkin } from './spriteSkin';
import { vectorSkin } from './vectorSkin';

/**
 * 스킨 고르기 (D-227·D-252) — **이 상수 하나**가 게임판 그림을 정한다.
 *
 * <ul>
 *   <li>{@code 'clay'} — 말랑 클레이 장난감(D-252, 기본). 시트 `skins/clay/` + 스킨 코드 `claySkin.ts`.</li>
 *   <li>{@code 'vector'} — 도형 스킨(D-223 그림 그대로). 시트를 받지 않는다.</li>
 *   <li>{@code 'sprite'} — 시트만으로 그리는 스킨(임시 그림 `skins/doodle/`).</li>
 * </ul>
 *
 * 시트를 받는 동안·실패하면 도형 스킨으로 그린다. 되돌리기는 여기를 {@code 'vector'} 로(`docs/artillery-skins.md`).
 */
export type SkinId = 'vector' | 'sprite' | 'clay';
export const ACTIVE_SKIN = 'clay' as SkinId;
/** 스킨 → 시트 폴더(`client/public/artillery/skins/{폴더}/`). */
export const SKIN_DIRS: Record<Exclude<SkinId, 'vector'>, string> = { sprite: 'doodle', clay: 'clay' };
/** 지금 고른 스킨의 시트 폴더(도형 스킨이면 임시 시트). */
export const SPRITE_SKIN_DIR: string = SKIN_DIRS[ACTIVE_SKIN === 'vector' ? 'sprite' : ACTIVE_SKIN];

type Loader = () => Promise<ArtillerySkin>;

/** 시트를 한 번만 받고, 다 받으면 구독자에게 알린다. 테스트가 로더를 바꿔 끼운다. */
export class SkinSource {
  private loaded: ArtillerySkin | null = null;
  private started = false;
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly id: SkinId,
    private readonly load: Loader,
  ) {}

  /** 지금 그릴 스킨 — 시트 스킨이 아직이면 도형 스킨(그리고 받기 시작). */
  current(): ArtillerySkin {
    if (this.id === 'vector') return vectorSkin;
    if (!this.started) {
      this.started = true;
      this.load().then(
        (skin) => {
          this.loaded = skin;
          for (const l of this.listeners) l();
        },
        (e: unknown) => {
          console.warn('[artillery] 스프라이트 스킨을 못 읽어 도형 스킨으로 그린다', e);
        },
      );
    }
    return this.loaded ?? vectorSkin;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
}

/** 시트 주소 — `public/` 은 빌드가 사이트 뿌리로 그대로 복사한다(카드 그림 `/cards/…` 와 같은 방식). */
const SPRITE_BASE = `/artillery/skins/${SPRITE_SKIN_DIR}/`;

/** 단위 테스트(jsdom)에서는 시트를 받지 않는다 — 받을 서버가 없다. */
const IN_TEST = (import.meta as { env?: { MODE?: string } }).env?.MODE === 'test';

export const skinSource = new SkinSource(IN_TEST ? 'vector' : ACTIVE_SKIN, () =>
  ACTIVE_SKIN === 'clay' ? loadSpriteSkin('clay', SPRITE_BASE, createClaySkin) : loadSpriteSkin(`sprite:${SPRITE_SKIN_DIR}`, SPRITE_BASE),
);

export type { ArtillerySkin } from './types';
