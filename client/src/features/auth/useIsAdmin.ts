import { useEffect, useState } from 'react';
import { authApi } from '@/api/auth';
import { useAuthStore } from './authStore';

/** 토큰별로 한 번만 묻는다(메인 ↔ 관리 화면을 오가도 다시 묻지 않는다). */
const cache = new Map<string, Promise<boolean>>();

function ask(token: string): Promise<boolean> {
  let p = cache.get(token);
  if (!p) {
    p = authApi
      .me(token)
      .then((me) => me.admin === true)
      .catch(() => {
        cache.delete(token); // 실패는 기억하지 않는다 — 다음에 다시 묻는다.
        return false;
      });
    cache.set(token, p);
  }
  return p;
}

/**
 * D-265 — 지금 로그인한 사람이 관리자인가(`GET /api/me` 의 `admin`). 아직 모르면 null.
 * 화면에 관리 버튼을 보일지 정하는 데만 쓴다 — 권한 판정은 서버 `/api/admin/**` 가 매번 한다.
 */
export function useIsAdmin(): boolean | null {
  const token = useAuthStore((s) => s.token);
  const guest = useAuthStore((s) => s.user?.guest === true);
  const [admin, setAdmin] = useState<boolean | null>(token && !guest ? null : false);

  useEffect(() => {
    if (!token || guest) {
      setAdmin(false);
      return;
    }
    let alive = true;
    setAdmin(null);
    ask(token).then((v) => {
      if (alive) setAdmin(v);
    });
    return () => {
      alive = false;
    };
  }, [token, guest]);

  return admin;
}

/** 테스트 전용. */
export function resetIsAdminForTest(): void {
  cache.clear();
}
