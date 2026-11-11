import type { ArtillerySkin } from './types';
import { loadSpriteSkin } from './spriteSkin';
import { vectorSkin } from './vectorSkin';

/**
 * 스킨 고르기 (D-227) — **이 상수 하나**가 게임판 그림을 정한다.
 *
 * <ul>
 *   <li>{@code 'vector'} — 도형 스킨(D-223 그림 그대로). 시트를 받지 않는다.</li>
 *   <li>{@code 'sprite'} — `client/public/artillery/skins/{@link SPRITE_SKIN_DIR}/` 의 시트. 받는 동안·실패하면 도형 스킨으로 그린다.</li>
 * </ul>
 *
 * 기본은 {@code 'vector'} — 지금 시트(`doodle`)는 진짜 그림이 오기 전의 임시 그림이라 도형 스킨보다 낫지 않다. 진짜 시트를 넣으면
 * 여기를 {@code 'sprite'} 로 바꾼다(`docs/artillery-skins.md`).
 */
export type SkinId = 'vector' | 'sprite';
export const ACTIVE_SKIN: SkinId = 'vector';
export const SPRITE_SKIN_DIR = 'doodle';

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

export const skinSource = new SkinSource(ACTIVE_SKIN, () => loadSpriteSkin(`sprite:${SPRITE_SKIN_DIR}`, SPRITE_BASE));

export type { ArtillerySkin } from './types';
