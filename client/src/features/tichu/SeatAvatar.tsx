import { useState } from 'react';

interface SeatAvatarProps {
  seat: number;
  /** 표시 사이즈 (px). 기본 48. */
  size?: number;
}

/**
 * Phase 8F — 좌석별 캐릭터 아바타. `/characters/seat-{0..3}.webp` 가 있으면 이미지,
 * 없으면 좌석 번호 숫자 fallback. 팀 색 (A=청, B=적) 으로 border 분기.
 */
export function SeatAvatar({ seat, size = 48 }: SeatAvatarProps) {
  const [failed, setFailed] = useState(false);
  const teamColor = seat % 2 === 0 ? '#5b8def' : '#e85d75';
  const src = `/characters/seat-${seat}.webp`;

  return (
    <span
      style={{
        display: 'inline-flex',
        width: size,
        height: size,
        borderRadius: '50%',
        border: `2px solid ${teamColor}`,
        overflow: 'hidden',
        background: '#1a1a1f',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: size * 0.4,
        fontWeight: 700,
        color: teamColor,
      }}
      aria-label={`Seat ${seat}`}
    >
      {failed ? (
        <span>{seat}</span>
      ) : (
        <img
          src={src}
          alt=""
          width={size}
          height={size}
          onError={() => setFailed(true)}
          draggable={false}
          style={{ display: 'block', objectFit: 'cover', width: '100%', height: '100%' }}
        />
      )}
    </span>
  );
}
