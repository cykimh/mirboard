import { useId } from 'react';

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

interface Props {
  /** 1~6, `null` 이면 빈 자리(아직 굴리지 않음). */
  face: number | null;
  /** 좌석 띠·점수표용 작은 주사위 — 그라데이션 없이 단색(18px 안팎에서 또렷하게). */
  mini?: boolean;
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
 * 상아 주사위(D-203). 가운데 판·좌석 띠·점수표 칸 이름·튜토리얼 퀴즈가 같은 그림을 쓴다. 꾸밈용이라 `aria-hidden` — 눈은 감싸는
 * 쪽의 `aria-label` 이 읽는다. 1 눈만 크고 붉다. 그라데이션 id 는 주사위마다 따로(`useId`) — 한 문서에 여러 개가 떠도 겹치지 않는다.
 */
export function YachtDie({ face, mini = false }: Props) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
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
          fill="#f7efdc"
          stroke="#a89972"
          strokeWidth="4"
        />
        {pips.map(([x, y], i) =>
          face === 1 ? (
            <circle key={i} className="yt-die-svg-one" cx={x} cy={y} r="16" fill="#a8243c" />
          ) : (
            <circle key={i} className="yt-die-svg-pip" cx={x} cy={y} r="10.5" fill="#1b1c1f" />
          ),
        )}
      </svg>
    );
  }
  const ivory = `yt-iv-${uid}`;
  const shade = `yt-sh-${uid}`;
  const pip = `yt-pp-${uid}`;
  const wine = `yt-wn-${uid}`;
  return (
    <svg className="yt-die-svg" viewBox="0 0 100 100" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={ivory} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#fffaf0" />
          <stop offset=".55" stopColor="#f3e9d2" />
          <stop offset="1" stopColor="#d9caa6" />
        </linearGradient>
        <linearGradient id={shade} x1="0" y1="0" x2="0" y2="1">
          <stop offset=".6" stopColor="#6b5a2e" stopOpacity="0" />
          <stop offset="1" stopColor="#6b5a2e" stopOpacity=".28" />
        </linearGradient>
        <radialGradient id={pip} cx=".35" cy=".3" r=".8">
          <stop offset="0" stopColor="#4a4a4a" />
          <stop offset=".6" stopColor="#17181a" />
          <stop offset="1" stopColor="#000" />
        </radialGradient>
        <radialGradient id={wine} cx=".35" cy=".3" r=".8">
          <stop offset="0" stopColor="#e06a7c" />
          <stop offset=".55" stopColor="#a8243c" />
          <stop offset="1" stopColor="#6d1324" />
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
        stroke="#a89972"
        strokeWidth="1.5"
      />
      <rect className="yt-die-svg-shade" x="3" y="3" width="94" height="94" rx="21" fill={`url(#${shade})`} />
      <path
        className="yt-die-svg-shine"
        d="M13 30 Q13 12 31 11 H69 Q87 12 87 30"
        fill="none"
        stroke="#fff"
        strokeOpacity=".75"
        strokeWidth="3"
        strokeLinecap="round"
      />
      {pips.map(([x, y], i) => {
        const r = face === 1 ? 13.5 : 8.6;
        return (
          <g key={i}>
            {/* 파인 눈 — 아래쪽 가장자리에 밝은 테가 살짝 비친다. */}
            <circle className="yt-die-svg-rim" cx={x} cy={y + 1.3} r={r + 0.4} fill="#fff" fillOpacity=".7" />
            <circle
              className={face === 1 ? 'yt-die-svg-one' : 'yt-die-svg-pip'}
              cx={x}
              cy={y}
              r={r}
              fill={`url(#${face === 1 ? wine : pip})`}
            />
          </g>
        );
      })}
    </svg>
  );
}
