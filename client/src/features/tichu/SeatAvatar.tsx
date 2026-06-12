import { useEffect, useState } from 'react';
import { avatarSrc } from '@/api/avatar';

interface SeatAvatarProps {
  seat: number;
  /** 좌석에 앉은 사용자 id. 업로드 아바타 조회 + 디폴트 동물 이모지 선택에 사용. */
  userId?: number;
  /** 표시 사이즈 (px). 기본 48. */
  size?: number;
  /** Phase 9D — 이 좌석이 봇이면 🤖 아이콘으로 차별화. */
  isBot?: boolean;
}

/** 디폴트 동물 캐릭터 풀. userId 해시로 결정적 배정(에셋 파일 불필요). */
const ANIMALS = [
  '🐶', '🐱', '🦊', '🐼', '🐯', '🦁', '🐸', '🐵',
  '🐰', '🐻', '🐨', '🐷', '🐹', '🐧', '🐢', '🐙',
];

function animalFor(userId: number | undefined, seat: number): string {
  const n = userId ?? seat;
  return ANIMALS[Math.abs(n) % ANIMALS.length];
}

/**
 * 좌석 아바타 — 업로드한 이미지가 있으면 그것(`/avatars/{userId}`), 없으면(404)
 * userId 해시 동물 이모지로 폴백(D-80). 팀 색(A=청, B=적) 링. 봇은 🤖.
 */
export function SeatAvatar({ seat, userId, size = 48, isBot = false }: SeatAvatarProps) {
  const [imgFailed, setImgFailed] = useState(false);
  // 좌석 주인이 바뀌면 이미지 시도를 다시 한다.
  useEffect(() => setImgFailed(false), [userId]);

  const teamColor = seat % 2 === 0 ? '#5b8def' : '#e85d75';
  const glyph = isBot ? '🤖' : animalFor(userId, seat);
  const showImg = !isBot && userId != null && !imgFailed;

  return (
    <span
      style={{
        display: 'inline-flex',
        width: size,
        height: size,
        borderRadius: '50%',
        border: `2px solid ${teamColor}`,
        overflow: 'hidden',
        background: isBot ? '#2a2a30' : '#1a1a1f',
        alignItems: 'center',
        justifyContent: 'center',
        lineHeight: 1,
        position: 'relative',
      }}
      aria-label={isBot ? `Bot seat ${seat}` : `Seat ${seat}`}
    >
      {showImg ? (
        <img
          src={avatarSrc(userId!)}
          alt=""
          width={size}
          height={size}
          onError={() => setImgFailed(true)}
          draggable={false}
          style={{ display: 'block', objectFit: 'cover', width: '100%', height: '100%' }}
        />
      ) : (
        <span style={{ fontSize: size * 0.58 }}>{glyph}</span>
      )}
    </span>
  );
}
