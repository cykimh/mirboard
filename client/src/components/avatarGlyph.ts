/**
 * 디폴트 동물 캐릭터 풀 — `userId` 해시로 결정적 배정(에셋 파일 불필요, D-80).
 *
 * <p>`features/tichu/SeatAvatar` 안에 있던 것을 D-103 에서 여기로 옮겼다. 이유는 하나 —
 * 같은 사용자가 티츄에선 🦊, 스컬킹에선 🐼 로 보이면 안 된다. 배열 순서가 곧 배정
 * 결과이므로 **순서를 바꾸면 기존 사용자의 아바타가 전부 변한다** (테스트로 고정).
 */
export const ANIMALS = [
  '🐶', '🐱', '🦊', '🐼', '🐯', '🦁', '🐸', '🐵',
  '🐰', '🐻', '🐨', '🐷', '🐹', '🐧', '🐢', '🐙',
] as const;

/** 업로드 아바타가 없을 때 쓸 동물 이모지. `userId` 가 없으면 좌석으로 폴백. */
export function animalFor(userId: number | undefined, seat: number): string {
  const n = userId ?? seat;
  return ANIMALS[Math.abs(n) % ANIMALS.length];
}
