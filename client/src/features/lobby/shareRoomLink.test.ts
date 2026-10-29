import { describe, expect, it, vi } from 'vitest';
import { shareRoomLink, type ShareEnv } from './shareRoomLink';

/**
 * D-133 — 초대 링크 공유 순서. ① 휴대폰 공유창(Web Share API) ② 없거나 실패하면 클립보드 복사 ③ 복사도
 * 막히면(권한·비보안 문맥·카톡 같은 앱 내 브라우저) 수동 복사. 공유창 취소는 조용히 끝낸다.
 * `navigator` 를 주입받는 순수 함수라 버튼은 결과만 그린다.
 */

const URL_ = 'https://mirboard.test/rooms/abc';
const META = { title: '원카드 같이 해요', text: '금요일 한 판' };

function abortError(): Error {
  return new DOMException('Share canceled', 'AbortError');
}

describe('shareRoomLink — 공유창 (D-133)', () => {
  it('공유창이 있으면 제목·문구·링크로 공유하고 복사하지 않는다', async () => {
    const env: ShareEnv = {
      share: vi.fn().mockResolvedValue(undefined),
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('shared');

    expect(env.share).toHaveBeenCalledWith({ title: META.title, text: META.text, url: URL_ });
    expect(env.clipboard!.writeText).not.toHaveBeenCalled();
  });

  it('공유창을 navigator 에 묶어 부른다 — 떼어 부르면 브라우저가 Illegal invocation 을 던진다', async () => {
    const env = {
      share(this: unknown) {
        if (this !== env) return Promise.reject(new TypeError('Illegal invocation'));
        return Promise.resolve();
      },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('shared');
  });

  it('공유창에서 취소(AbortError)하면 cancelled — 복사로 넘어가지 않는다', async () => {
    const env: ShareEnv = {
      share: vi.fn().mockRejectedValue(abortError()),
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('cancelled');
    expect(env.clipboard!.writeText).not.toHaveBeenCalled();
  });

  it('공유창이 다른 이유로 실패(NotAllowedError)하면 복사로 넘어간다', async () => {
    const env: ShareEnv = {
      share: vi.fn().mockRejectedValue(new DOMException('no gesture', 'NotAllowedError')),
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('copied');
    expect(env.clipboard!.writeText).toHaveBeenCalledWith(URL_);
  });
});

describe('shareRoomLink — 복사·수동 (D-133)', () => {
  it('공유창이 없으면 링크를 복사한다', async () => {
    const env: ShareEnv = { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('copied');
    expect(env.clipboard!.writeText).toHaveBeenCalledWith(URL_);
  });

  it('복사가 거절되면 manual — 링크를 직접 복사하게 한다', async () => {
    const env: ShareEnv = {
      clipboard: { writeText: vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('manual');
  });

  it('공유창도 클립보드도 없으면(카톡 앱 내 브라우저) manual', async () => {
    await expect(shareRoomLink(URL_, META, {})).resolves.toBe('manual');
  });

  it('공유창이 실패하고 클립보드가 없으면 manual', async () => {
    const env: ShareEnv = { share: vi.fn().mockRejectedValue(new TypeError('boom')) };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('manual');
  });
});
