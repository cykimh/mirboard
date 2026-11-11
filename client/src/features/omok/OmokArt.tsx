import type { OmokStone } from '@/types/omok';

/**
 * 오목 그림 조각 (D-192 "결" 테마). 좌표는 교차점 간격 1, 돌 중심이 원점이다. 여기 조각은 모두 `.om-art` 라 테마 클래스가
 * 없으면 보이지 않고, 그때는 납작한 원(`.om-flat`)이 대신 보인다 — 고르는 것은 CSS 다.
 *
 * <p>그라데이션은 판 SVG 마다 자기 `<defs>` 를 가진다(`useId` 접두). 숨은 SVG 의 defs 를 다른 SVG 가 참조하면 브라우저에 따라
 * 칠이 빠지기 때문이다.
 */

/** `useId()` 값을 SVG id 로 쓸 수 있게(콜론 제거). */
export const artId = (reactId: string) => `om${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`;

export function OmokDefs({ id }: { id: string }) {
  return (
    <defs>
      {/* 흑석 — 무광 슬레이트. 등불이 왼쪽 위에서 온다. */}
      <radialGradient id={`${id}-bk`} cx="0.36" cy="0.3" r="0.78">
        <stop offset="0" stopColor="#6d717c" />
        <stop offset="0.34" stopColor="#292a30" />
        <stop offset="1" stopColor="#060608" />
      </radialGradient>
      {/* 조개 백돌 — 가운데가 밝고 가장자리가 따뜻한 회색으로 진다. */}
      <radialGradient id={`${id}-wh`} cx="0.38" cy="0.32" r="0.8">
        <stop offset="0" stopColor="#ffffff" />
        <stop offset="0.55" stopColor="#f5f0e4" />
        <stop offset="1" stopColor="#c6bba2" />
      </radialGradient>
      <radialGradient id={`${id}-sh`}>
        <stop offset="0.5" stopColor="#2a1500" stopOpacity="0.5" />
        <stop offset="1" stopColor="#2a1500" stopOpacity="0" />
      </radialGradient>
      {/* 연출용 — 필터 없이 가장자리가 풀리는 빛·잉크. */}
      <radialGradient id={`${id}-glow`}>
        <stop offset="0" stopColor="#fffbe6" stopOpacity="1" />
        <stop offset="0.4" stopColor="#f7d36a" stopOpacity="0.7" />
        <stop offset="1" stopColor="#f7d36a" stopOpacity="0" />
      </radialGradient>
      <radialGradient id={`${id}-ink`}>
        <stop offset="0" stopColor="#b3261e" stopOpacity="0.85" />
        <stop offset="0.65" stopColor="#b3261e" stopOpacity="0.5" />
        <stop offset="1" stopColor="#b3261e" stopOpacity="0" />
      </radialGradient>
    </defs>
  );
}

/** 돌 아래 그림자 — 돌보다 먼저(아래 층에) 그려 옆 돌을 덮지 않게 한다. */
export function StoneShadow({ id }: { id: string }) {
  return <ellipse className="om-art om-stone-shadow" cx={0.05} cy={0.08} rx={0.51} ry={0.49} fill={`url(#${id}-sh)`} />;
}

/** 돌 한 개 — 납작한 원 + 그림 조각. `seed` 는 조개 줄무늬 각도를 돌마다 다르게 한다(수 번호를 주면 된다). */
export function StoneBody({ id, color, seed = 0 }: { id: string; color: OmokStone; seed?: number }) {
  return (
    <>
      <circle className="om-flat" r={0.44} />
      {color === 'BLACK' ? (
        <g className="om-art">
          <circle r={0.44} fill={`url(#${id}-bk)`} />
          <ellipse
            cx={-0.15}
            cy={-0.2}
            rx={0.16}
            ry={0.075}
            fill="#ffffff"
            opacity={0.16}
            transform="rotate(-32 -0.15 -0.2)"
          />
          <path
            d="M-0.2 0.33A0.39 0.39 0 0 0 0.33 0.2"
            fill="none"
            stroke="#aeb4c4"
            strokeWidth={0.02}
            strokeLinecap="round"
            opacity={0.2}
          />
        </g>
      ) : (
        <g className="om-art">
          <circle r={0.44} fill={`url(#${id}-wh)`} stroke="#9c9078" strokeWidth={0.014} />
          <path
            transform={`rotate(${(seed * 53) % 180})`}
            d="M-0.33 0.1Q0 0.27 0.33 0.1M-0.38 -0.02Q0 0.17 0.38 -0.02M-0.34 -0.15Q0 0.04 0.34 -0.15"
            fill="none"
            stroke="#b3a585"
            strokeWidth={0.012}
            opacity={0.5}
          />
          <ellipse
            cx={-0.14}
            cy={-0.19}
            rx={0.15}
            ry={0.07}
            fill="#ffffff"
            opacity={0.75}
            transform="rotate(-32 -0.14 -0.19)"
          />
        </g>
      )}
    </>
  );
}

/** 5목 반짝임 — 네 갈래 별. */
export const GLINT_PATH = 'M0 -0.3L0.045 -0.045 0.3 0 0.045 0.045 0 0.3 -0.045 0.045 -0.3 0 -0.045 -0.045Z';
