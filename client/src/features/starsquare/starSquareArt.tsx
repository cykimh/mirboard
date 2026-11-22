/**
 * 별자리 수판 그림 조각(D-286) — 전부 직접 그린 인라인 SVG. 외부 자산 없음.
 *
 * <p>규칙: `id` 를 쓰지 않는다(같은 그림이 한 화면에 여러 번 뜬다 — 좌석마다 망원경). 색은 `currentColor` 와 `--sq-*` 토큰만 쓴다
 * (라이트·다크는 토큰이 바꾼다). 전부 장식이라 `aria-hidden` 이고 눌림을 받지 않는다(`pointer-events: none` 은 CSS `.sq-art`).
 */

interface ArtProps {
  className?: string;
  size?: number;
}

const cls = (base: string, extra?: string) => (extra ? `sq-art ${base} ${extra}` : `sq-art ${base}`);

/** 네 갈래 별(✦) 경로 — 가운데 (x, y), 팔 길이 s. 안쪽이 오목해 반짝이는 모양이 된다. */
export function sparkPath(x: number, y: number, s: number): string {
  const k = s * 0.2;
  return (
    `M${x} ${y - s}Q${x + k} ${y - k} ${x + s} ${y}Q${x + k} ${y + k} ${x} ${y + s}` +
    `Q${x - k} ${y + k} ${x - s} ${y}Q${x - k} ${y - k} ${x} ${y - s}z`
  );
}

/** 좌석 띠의 작은 망원경 — 삼각대 위 경통이 오른쪽 위 하늘을 본다. 끝에 작은 별 하나. */
export function TelescopeArt({ className, size = 18 }: ArtProps) {
  return (
    <svg className={cls('sq-art-telescope', className)} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {/* 삼각대 */}
      <path d="M11 14.5 7.5 22.5M11 14.5l4 8M11 14.5V22" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      {/* 경통 */}
      <path
        d="M3.4 14.2l11.8-6.8 2.3 4-11.8 6.8z"
        fill="var(--sq-scope, currentColor)"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
      <path d="M15.2 7.4l2.7-1.5 2.3 4-2.7 1.5z" fill="var(--sq-star)" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      {/* 보는 별 */}
      <path className="sq-art-glint" d={sparkPath(21, 3.2, 2.4)} fill="var(--sq-star)" />
    </svg>
  );
}

/** 준비 화면의 천문대 — 언덕 위 둥근 지붕, 열린 틈으로 망원경이 나와 있고 위로 별 세 개가 한 줄로 이어진다. */
export function ObservatoryArt({ className, size = 132 }: ArtProps) {
  return (
    <svg
      className={cls('sq-art-observatory', className)}
      width={size}
      height={(size * 96) / 132}
      viewBox="0 0 132 96"
      aria-hidden="true"
      focusable="false"
    >
      {/* 별자리 — 지붕 틈이 가리키는 쪽 */}
      <path className="sq-art-thread" d="M82 30L98 16L118 22" fill="none" stroke="var(--sq-constellation)" strokeWidth="1.4" strokeLinecap="round" />
      <path className="sq-art-star sq-art-star-1" d={sparkPath(82, 30, 4.5)} fill="var(--sq-star)" />
      <path className="sq-art-star sq-art-star-2" d={sparkPath(98, 16, 6)} fill="var(--sq-star)" />
      <path className="sq-art-star sq-art-star-3" d={sparkPath(118, 22, 4)} fill="var(--sq-star)" />
      <circle className="sq-art-star sq-art-star-2" cx="20" cy="18" r="1.4" fill="var(--sq-star)" />
      <circle className="sq-art-star sq-art-star-3" cx="40" cy="8" r="1.1" fill="var(--sq-star)" />
      <circle className="sq-art-star sq-art-star-1" cx="10" cy="44" r="1" fill="var(--sq-star)" />
      {/* 초승달 */}
      <path d="M30 26a9 9 0 1 0 9 11.5A7.5 7.5 0 0 1 30 26z" fill="var(--sq-moon)" />
      {/* 언덕 */}
      <path d="M0 96V84Q34 66 66 72T132 80V96z" fill="var(--sq-hill)" />
      {/* 건물 몸통 */}
      <path d="M46 78V58h40v20z" fill="var(--sq-dome-wall)" stroke="var(--sq-dome-line)" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M62 78V66h8v12z" fill="var(--sq-dome-line)" />
      <path d="M52 64h5v5h-5zM75 64h5v5h-5z" fill="var(--sq-star)" fillOpacity="0.85" />
      {/* 둥근 지붕 + 열린 틈 */}
      <path d="M44 58a22 22 0 0 1 44 0z" fill="var(--sq-dome)" stroke="var(--sq-dome-line)" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M66 36.2l9.5 1.8L71 58h-8z" fill="var(--sq-dome-line)" />
      {/* 망원경 */}
      <path d="M67 52l11-13 4.2 3.6-11 13z" fill="var(--sq-star)" stroke="var(--sq-dome-line)" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M42 58h48" stroke="var(--sq-dome-line)" strokeWidth="2.4" strokeLinecap="round" />
    </svg>
  );
}

/** 매치 끝 1위 — 별 다섯 개가 왕관 모양 별자리로 이어진다. */
export function CrownConstellationArt({ className, size = 96 }: ArtProps) {
  const pts: [number, number, number][] = [
    [10, 18, 4],
    [28, 38, 3.4],
    [48, 8, 6.5],
    [68, 38, 3.4],
    [86, 18, 4],
  ];
  return (
    <svg
      className={cls('sq-art-crown', className)}
      width={size}
      height={(size * 56) / 96}
      viewBox="0 0 96 56"
      aria-hidden="true"
      focusable="false"
    >
      <path
        className="sq-art-thread"
        d="M10 18L28 38L48 8L68 38L86 18L80 50H16z"
        fill="var(--sq-star)"
        fillOpacity="0.12"
        stroke="var(--sq-constellation)"
        strokeWidth="1.5"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      {pts.map(([x, y, s], i) => (
        <path key={i} className={`sq-art-star sq-art-star-${(i % 3) + 1}`} d={sparkPath(x, y, s)} fill="var(--sq-star)" />
      ))}
      <circle cx="16" cy="50" r="1.8" fill="var(--sq-star)" />
      <circle cx="80" cy="50" r="1.8" fill="var(--sq-star)" />
    </svg>
  );
}

/** 지우기 — 별 하나를 끄는 지우개. */
export function EraseIcon({ className, size = 16 }: ArtProps) {
  return (
    <svg className={cls('sq-art-icon', className)} width={size} height={size} viewBox="0 0 20 20" aria-hidden="true" focusable="false">
      <path d="M7.5 16.5L3 12l8.5-8.5 5.5 5.5-7.5 7.5z" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
      <path d="M6.5 8.5l5 5M9.5 16.5h8" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

/** 되돌리기 — 왼쪽으로 도는 화살. */
export function UndoIcon({ className, size = 16 }: ArtProps) {
  return (
    <svg className={cls('sq-art-icon', className)} width={size} height={size} viewBox="0 0 20 20" aria-hidden="true" focusable="false">
      <path d="M4 8h8a4.5 4.5 0 0 1 0 9H8" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7.5 4.5L4 8l3.5 3.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
