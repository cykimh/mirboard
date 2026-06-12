/**
 * 아바타(D-80). 조회는 공개 `/avatars/{userId}`(img src 로 직접), 업로드/삭제는
 * 인증 `/api/me/avatar`(multipart / DELETE). apiRequest 는 JSON 전용이라 별도 처리.
 */

/** 좌석/프로필 아바타 이미지 URL. version 으로 업로드 후 캐시 무력화. */
export function avatarSrc(userId: number, version?: number): string {
  return version ? `/avatars/${userId}?v=${version}` : `/avatars/${userId}`;
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const json = JSON.parse(await res.text());
    return json?.error?.message ?? fallback;
  } catch {
    return fallback;
  }
}

export const avatarApi = {
  async upload(token: string, file: File): Promise<void> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/api/me/avatar', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    if (!res.ok) {
      throw new Error(await errorMessage(res, '아바타 업로드에 실패했습니다'));
    }
  },

  async remove(token: string): Promise<void> {
    const res = await fetch('/api/me/avatar', {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok && res.status !== 204) {
      throw new Error(await errorMessage(res, '아바타 삭제에 실패했습니다'));
    }
  },
};
