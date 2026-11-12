/**
 * D-265 — 게임 음성 클립. 재생은 공개 `/sounds`(키 → 버전)·`/sounds/{key}`(비-`/api` — `<audio>` 는 Bearer 를 못 싣는다),
 * 올리기·지우기·관리 목록은 어드민 `/api/admin/sounds`. 올리기는 multipart 라 apiRequest(JSON 전용)를 쓰지 않는다.
 */
import { apiRequest } from './client';

export interface SoundClipInfo {
  key: string;
  contentType: string;
  byteSize: number;
  /** 바뀐 시각(ms) — 재생 주소의 버전. */
  updatedAt: number;
}

export interface AdminSoundsResponse {
  /** 서버가 받는 키 전부(허용 목록). */
  keys: string[];
  clips: SoundClipInfo[];
  maxBytes: number;
}

/** 클립 재생 주소. 버전을 붙여 바뀐 파일이 캐시에 가리지 않게 한다. */
export function soundSrc(key: string, version: number): string {
  return `/sounds/${encodeURIComponent(key)}?v=${version}`;
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const json = JSON.parse(await res.text());
    return json?.error?.message ?? fallback;
  } catch {
    return fallback;
  }
}

export const soundsApi = {
  /** 공개 — 올라와 있는 키 → 버전. 인증 없음. */
  async versions(): Promise<Record<string, number>> {
    const res = await fetch('/sounds', { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error('음성 목록을 받지 못했습니다');
    const json = (await res.json()) as { clips?: Record<string, number> };
    return json.clips ?? {};
  },

  adminList(token: string): Promise<AdminSoundsResponse> {
    return apiRequest('/api/admin/sounds', { token });
  },

  async upload(token: string, key: string, file: File): Promise<SoundClipInfo> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(`/api/admin/sounds/${encodeURIComponent(key)}`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    if (!res.ok) {
      throw new Error(await errorMessage(res, '음성 올리기에 실패했습니다'));
    }
    return (await res.json()) as SoundClipInfo;
  },

  remove(token: string, key: string): Promise<void> {
    return apiRequest<void>(`/api/admin/sounds/${encodeURIComponent(key)}`, { method: 'DELETE', token });
  },
};
