import { useEffect, useState } from 'react';
import { usersApi } from '@/api/users';

/**
 * D-201 — 판을 시작할 때의 내 요트 최고 점수. 종료 화면이 이번 총점과 견줘 "새 최고 기록!"을 판단한다(서버는 판이 끝나면 이미 새 값을
 * 기록하므로 시작 때 값을 들고 있어야 한다). `undefined` = 아직 못 받음·안 물음, `null` = 기록 없음.
 */
export function useYachtBest(token: string | null, userId: number, enabled: boolean): number | null | undefined {
  const [best, setBest] = useState<number | null | undefined>(undefined);
  useEffect(() => {
    if (!enabled || !token) return;
    let cancelled = false;
    usersApi
      .stats(token, userId)
      .then((st) => {
        if (!cancelled) setBest(st.games.find((g) => g.gameType === 'YACHT')?.bestScore ?? null);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [token, userId, enabled]);
  return best;
}
