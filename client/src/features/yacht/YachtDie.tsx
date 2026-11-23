import { useId } from 'react';
import { DICE_PALETTES, useYachtSkinStore, type DiceSkin } from './yachtSkins';

/** 눈 자리(100×100 좌표). */
const PIPS: Record<number, ReadonlyArray<readonly [number, number]>> = {
  1: [[50, 50]],
  2: [
    [30, 30],
    [70, 70],
  ],
  3: [
    [28, 28],
    [50, 50],
    [72, 72],
  ],
  4: [
    [30, 30],
    [70, 30],
    [30, 70],
    [70, 70],
  ],
  5: [
    [28, 28],
    [72, 28],
    [50, 50],
    [28, 72],
    [72, 72],
  ],
  6: [
    [30, 26],
    [70, 26],
    [30, 50],
    [70, 50],
    [30, 74],
    [70, 74],
  ],
};

/** 기본(상아) 작은 주사위의 눈 색 — 여섯 면 모두 같다(D-264). 큰 주사위는 같은 검정의 그라데이션(`yt-pp-*`). 다른 스킨은 `DICE_PALETTES`. */
export const PIP_COLOR = DICE_PALETTES.ivory.miniPip;

interface Props {
  /** 1~6, `null` 이면 빈 자리(아직 굴리지 않음). */
  face: number | null;
  /** 좌석 띠·점수표용 작은 주사위 — 그라데이션 없이 단색(18px 안팎에서 또렷하게). */
  mini?: boolean;
  /** D-296 — 이 스킨으로 그린다(꾸미기 창의 견본). 없으면 내가 고른 스킨. */
  skin?: DiceSkin;
}

/** 앞면이 `face` 일 때 나머지 다섯 면(뒤·오른쪽·왼쪽·위·아래) — 마주 보는 면의 합은 7. */
function cubeSides(face: number): number[] {
  const back = 7 - face;
  const rest = [1, 2, 3, 4, 5, 6].filter((n) => n !== face && n !== back);
  return [back, rest[0], 7 - rest[0], rest[1], 7 - rest[1]];
}

const CUBE_SIDE_CLASS = ['back', 'right', 'left', 'top', 'bottom'];

/**
 * 굴러오는 동안의 주사위 — 진짜 여섯 면 정육면체(CSS 3D). 앞면이 서버가 준 눈이라 회전이 0 으로 돌아오면 그대로 그 눈으로 멈춘다
 * (난수로 눈을 바꿔 보여 주지 않는다). 옆면은 단색 그림(600ms 동안만 뜨므로 가볍게).
 */
export function YachtDieCube({ face }: { face: number }) {
  return (
    <span className="yt-cube">
      <span className="yt-cube-side yt-cube-front">
        <YachtDie face={face} />
      </span>
      {cubeSides(face).map((n, i) => (
        <span key={i} className={`yt-cube-side yt-cube-${CUBE_SIDE_CLASS[i]}`}>
          <YachtDie face={n} mini />
        </span>
      ))}
    </span>
  );
}

/**
 * 주사위 그림(D-203 상아 — D-296 부터 스킨의 색표로 칠한다, 모양은 하나). 가운데 판·좌석 띠·점수표 칸 이름·튜토리얼 퀴즈가 같은 그림을 쓴다. 꾸밈용이라 `aria-hidden` — 눈은 감싸는
 * 쪽의 `aria-label` 이 읽는다. 1 눈은 크기만 크고 색은 다른 눈과 같은 검정이다(D-264 — 붉은 눈을 없앴다). 그라데이션 id 는 주사위마다 따로(`useId`) — 한 문서에 여러 개가 떠도 겹치지 않는다.
 */
export function YachtDie({ face, mini = false, skin }: Props) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const chosen = useYachtSkinStore((st) => st.dice);
  const p = DICE_PALETTES[skin ?? chosen] ?? DICE_PALETTES.ivory;
  const pips = face ? PIPS[face] : [];
  if (mini) {
    return (
      <svg className="yt-die-svg yt-die-svg-mini" viewBox="0 0 100 100" aria-hidden="true" focusable="false">
        <rect
          className="yt-die-svg-body"
          x="4"
          y="4"
          width="92"
          height="92"
          rx="22"
          fill={p.mini}
          stroke={p.miniStroke}
          strokeWidth="4"
        />
        {pips.map(([x, y], i) => (
          <circle
            key={i}
            className={face === 1 ? 'yt-die-svg-pip yt-die-svg-one' : 'yt-die-svg-pip'}
            cx={x}
            cy={y}
            r={face === 1 ? 16 : 10.5}
            fill={p.miniPip}
          />
        ))}
      </svg>
    );
  }
  const ivory = `yt-iv-${uid}`;
  const shade = `yt-sh-${uid}`;
  const pip = `yt-pp-${uid}`;
  return (
    <svg className="yt-die-svg" viewBox="0 0 100 100" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={ivory} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={p.body[0]} />
          <stop offset=".55" stopColor={p.body[1]} />
          <stop offset="1" stopColor={p.body[2]} />
        </linearGradient>
        <linearGradient id={shade} x1="0" y1="0" x2="0" y2="1">
          <stop offset=".6" stopColor={p.shade} stopOpacity="0" />
          <stop offset="1" stopColor={p.shade} stopOpacity=".28" />
        </linearGradient>
        <radialGradient id={pip} cx=".35" cy=".3" r=".8">
          <stop offset="0" stopColor={p.pip[0]} />
          <stop offset=".6" stopColor={p.pip[1]} />
          <stop offset="1" stopColor={p.pip[2]} />
        </radialGradient>
      </defs>
      <rect
        className="yt-die-svg-body"
        x="3"
        y="3"
        width="94"
        height="94"
        rx="21"
        fill={`url(#${ivory})`}
        stroke={p.stroke}
        strokeWidth="1.5"
      />
      {/* 나뭇결(호두나무) — 옅은 결 세 줄. 빈 자리에는 그리지 않는다. */}
      {p.grain && face !== null && (
        <g className="yt-die-svg-grain" fill="none" stroke={p.grain} strokeOpacity=".28" strokeWidth="1.6" strokeLinecap="round">
          <path d="M8 24 C30 16 62 30 92 20" />
          <path d="M6 52 C28 44 66 60 94 48" />
          <path d="M9 80 C34 72 60 86 91 76" />
        </g>
      )}
      <rect className="yt-die-svg-shade" x="3" y="3" width="94" height="94" rx="21" fill={`url(#${shade})`} />
      <path
        className="yt-die-svg-shine"
        d="M13 30 Q13 12 31 11 H69 Q87 12 87 30"
        fill="none"
        stroke={p.shine}
        strokeOpacity={p.shineOpacity}
        strokeWidth="3"
        strokeLinecap="round"
      />
      {pips.map(([x, y], i) => {
        const r = face === 1 ? 13.5 : 8.6;
        return (
          <g key={i}>
            {/* 파인 눈 — 아래쪽 가장자리에 밝은 테가 살짝 비친다. */}
            <circle className="yt-die-svg-rim" cx={x} cy={y + 1.3} r={r + 0.4} fill={p.rim} fillOpacity={p.rimOpacity} />
            <circle
              className={face === 1 ? 'yt-die-svg-pip yt-die-svg-one' : 'yt-die-svg-pip'}
              cx={x}
              cy={y}
              r={r}
              fill={`url(#${pip})`}
            />
          </g>
        );
      })}
    </svg>
  );
}
