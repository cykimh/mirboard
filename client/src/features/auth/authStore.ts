import { create } from 'zustand';
import { authApi } from '@/api/auth';
import type { LoginResponse } from '@/types/api';

const STORAGE_KEY = 'mirboard.auth';

/** D-117 — `guest` 는 서버 응답 그대로. D-117 이전 스냅샷엔 없으므로 없으면 정회원으로 본다. */
type AuthUser = { userId: number; username: string; guest?: boolean };

interface AuthSnapshot {
  token: string;
  expiresAt: number;
  user: AuthUser;
}

interface AuthState {
  token: string | null;
  expiresAt: number | null;
  user: AuthUser | null;
  login: (username: string, password: string) => Promise<void>;
  /** D-117 — 가입 없는 체험. login 과 같은 영속 경로. */
  loginAsGuest: () => Promise<void>;
  register: (username: string, password: string) => Promise<void>;
  logout: () => void;
  loadFromStorage: () => void;
}

function snapshotOf(res: LoginResponse): AuthSnapshot {
  return { token: res.accessToken, expiresAt: res.expiresAt, user: res.user };
}

function persist(snapshot: AuthSnapshot | null) {
  if (snapshot) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
  } else {
    localStorage.removeItem(STORAGE_KEY);
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: null,
  expiresAt: null,
  user: null,

  async register(username, password) {
    await authApi.register(username, password);
  },

  async login(username, password) {
    const snapshot = snapshotOf(await authApi.login(username, password));
    persist(snapshot);
    set(snapshot);
  },

  async loginAsGuest() {
    const snapshot = snapshotOf(await authApi.guest());
    persist(snapshot);
    set(snapshot);
  },

  logout() {
    persist(null);
    set({ token: null, expiresAt: null, user: null });
  },

  loadFromStorage() {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    try {
      const snap = JSON.parse(raw) as AuthSnapshot;
      if (snap.expiresAt && snap.expiresAt < Date.now()) {
        persist(null);
        return;
      }
      set(snap);
    } catch {
      persist(null);
    }
    // Trigger a no-op read so devtools sees a settled state.
    get();
  },
}));
