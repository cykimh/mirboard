import type { CSSProperties } from 'react';
import { BAND_LABEL, tempBand } from './wordTempMath';

/**
 * 낱말 온도 그림 조각(D-319) — 전부 직접 그린 인라인 SVG·CSS. 외부 자산 없음.
 *
 * <p>규칙: `id` 를 쓰지 않는다(같은 그림이 한 화면에 여러 번 뜬다). 색은 `currentColor` 와 `--wt-*` 토큰만 쓴다(라이트·다크는 토큰이
 * 바꾼다). 장식 그림은 `aria-hidden` 이고 눌림을 받지 않는다(`pointer-events: none` 은 CSS `.wt-art`).
 */

interface GaugeProps {
  /** 온도 0~100. */
  temp: number;
  /** 최저 온도 눈금(있으면 관 위에 선을 긋는다). */
  floor?: number;
  /** 작은 줄(내 힌트 목록·차례 끝 목록). */
  small?: boolean;
  /** 방금 공개·채점됐다 — 수은주가 0 에서 차오른다(연출이 켜져 있을 때만 CSS 가 움직인다). */
  fresh?: boolean;
  className?: string;
}

/**
 * 온도계 한 줄 — 왼쪽 둥근 구(球)에서 유리관으로 수은주가 온도만큼 차 있고, 오른쪽에 숫자. 띠(차가움·미지근·뜨거움·거의 정답)마다 색이
 * 다르고, 색만으로 가르지 않게 숫자와 띠 이름(`aria-label`)을 같이 둔다. 96 이상은 관 안에 방울이 올라온다.
 */
export function TempGauge({ temp, floor, small = false, fresh = false, className }: GaugeProps) {
  const t = Math.max(0, Math.min(100, Math.round(temp)));
  const band = tempBand(t);
  const style = {
    '--wt-temp': t / 100,
    ...(floor !== undefined ? { '--wt-floor': floor / 100 } : {}),
  } as CSSProperties;
  return (
    <span
      className={`wt-gauge wt-band-${band}${small ? ' wt-gauge-sm' : ''}${fresh ? ' wt-gauge-fresh' : ''}${className ? ` ${className}` : ''}`}
      style={style}
      role="img"
      aria-label={`온도 ${t}도, ${BAND_LABEL[band]}`}
      data-temp={t}
    >
      <span className="wt-gauge-bulb" aria-hidden="true" />
      <span className="wt-gauge-tube" aria-hidden="true">
        <span className="wt-gauge-fill" />
        {band === 'boil' && (
          <>
            <span className="wt-gauge-bubble" />
            <span className="wt-gauge-bubble" />
            <span className="wt-gauge-bubble" />
          </>
        )}
        {floor !== undefined && <span className="wt-gauge-floor" />}
      </span>
      <span className="wt-gauge-num" aria-hidden="true">
        {t}
        <span className="wt-gauge-deg">°</span>
      </span>
    </span>
  );
}

interface ArtProps {
  className?: string;
  size?: number;
}

/** 세워 둔 유리 온도계 — 차례 끝·준비 화면의 장식. 수은주 높이는 `level`(0~1). */
export function ThermometerArt({ className, size = 56, level = 0.6 }: ArtProps & { level?: number }) {
  const top = 30 - Math.max(0, Math.min(1, level)) * 22;
  return (
    <svg
      className={`wt-art wt-art-thermo${className ? ` ${className}` : ''}`}
      width={size * 0.5}
      height={size}
      viewBox="0 0 24 48"
      aria-hidden="true"
      focusable="false"
    >
      <path
        d="M8 6a4 4 0 0 1 8 0v24.2a8 8 0 1 1-8 0z"
        fill="var(--wt-glass)"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path d={`M10.6 ${top}h2.8v${34 - top}h-2.8z`} fill="var(--wt-mercury)" />
      <circle cx="12" cy="37" r="5" fill="var(--wt-mercury)" />
      <path d="M17.5 12h2.5M17.5 18h2.5M17.5 24h2.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <circle cx="10.3" cy="35.3" r="1.3" fill="#fff" fillOpacity="0.7" />
    </svg>
  );
}

/** 좌석의 출제자 표식 — 작은 쪽지(낱말을 적어 숨긴 사람). */
export function NoteArt({ className, size = 14 }: ArtProps) {
  return (
    <svg
      className={`wt-art${className ? ` ${className}` : ''}`}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M3 2.5h7l3 3v8H3z" fill="var(--wt-note, currentColor)" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M5.5 7.5h5M5.5 10.3h3.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  );
}
