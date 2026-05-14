import { create } from 'zustand';
import { authApi } from '@/api/auth';

const STORAGE_KEY = 'mirboard.auth';

interface AuthSnapshot {
  token: string;
  expiresAt: number;
  user: { userId: number; username: string };
}

interface AuthState {
  token: string | null;
  expiresAt: number | null;
  user: { userId: number; username: string } | null;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, password: string) => Promise<void>;
  logout: () => void;
  loadFromStorage: () => void;
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
    const res = await authApi.login(username, password);
    const snapshot: AuthSnapshot = {
      token: res.accessToken,
      expiresAt: res.expiresAt,
      user: res.user,
    };
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
