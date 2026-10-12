/**
 * D-133 — 방 초대 링크 공유. 버튼은 결과만 그린다.
 *
 * <p>순서: ① 휴대폰 공유창(Web Share API — 카톡 포함 설치된 앱 목록) ② 없거나 실패하면 클립보드 복사 ③ 복사도
 * 막히면(권한·비보안 문맥·일부 앱 내 브라우저) `'manual'` — 화면이 링크가 선택된 입력란을 보인다. 공유창에서
 * 사용자가 취소하면(`AbortError`) 조용히 끝낸다(`'cancelled'`). 카카오 SDK 공유는 앱 키 등록이 필요해 후속.
 */
export type ShareResult = 'shared' | 'copied' | 'manual' | 'cancelled';

export interface ShareMeta {
  title: string;
  text: string;
}

/** `navigator` 의 필요한 부분만. 테스트와 앱 내 브라우저(둘 다 없음)를 같은 모양으로 다룬다. */
export interface ShareEnv {
  share?: (data: ShareData) => Promise<void>;
  clipboard?: { writeText(text: string): Promise<void> };
}

function isAbort(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError';
}

export async function shareRoomLink(url: string, meta: ShareMeta, env: ShareEnv): Promise<ShareResult> {
  if (typeof env.share === 'function') {
    try {
      // 메서드로 부른다 — `navigator.share` 를 떼어 부르면 브라우저가 Illegal invocation 을 던진다.
      await env.share({ title: meta.title, text: meta.text, url });
      return 'shared';
    } catch (err) {
      if (isAbort(err)) return 'cancelled';
      // 그 밖의 실패(사용자 제스처 없음·미지원 데이터 등)는 복사로 넘어간다.
    }
  }
  if (env.clipboard && typeof env.clipboard.writeText === 'function') {
    try {
      await env.clipboard.writeText(url);
      return 'copied';
    } catch {
      // 권한 거절·비보안 문맥 — 수동 복사로.
    }
  }
  return 'manual';
}
