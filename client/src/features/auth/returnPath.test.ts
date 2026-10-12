import { describe, expect, it } from 'vitest';
import { safeReturnPath } from './returnPath';

/**
 * D-133 — 로그인 뒤 돌아갈 주소. 보호 경로가 `/login` 으로 보낼 때 `state.from` 에 가려던 경로를 싣고,
 * 로그인·게스트 입장·가입 뒤 그 경로로 돌려보낸다. 그 값은 화면 밖에서 조작될 수 있으므로 **사이트 안
 * 경로만** 받는다 — 외부로 튕기는 오픈 리다이렉트와 로그인 화면으로의 되돌이를 막는다.
 */

const ORIGIN = 'https://mirboard.test';

/** 브라우저 URL 파서 기준으로 이 값이 사이트 안에 머무는가. */
function staysOnSite(path: string): boolean {
  return new URL(path, ORIGIN).origin === ORIGIN;
}

describe('safeReturnPath — 사이트 안 경로는 그대로 (D-133)', () => {
  it.each(['/rooms/abc-123', '/rooms/abc?tab=1#chat', '/games', '/profile', '/'])(
    '%s 를 그대로 돌려준다',
    (path) => {
      expect(safeReturnPath(path)).toBe(path);
      expect(staysOnSite(path)).toBe(true);
    },
  );

  it('인코딩된 `/%2F%2Fevil.com` 은 사이트 안 경로로만 남는다 — 브라우저가 풀어 `//` 로 읽지 않는다', () => {
    const out = safeReturnPath('/%2F%2Fevil.com');

    expect(out).toBe('/%2F%2Fevil.com');
    expect(staysOnSite(out!)).toBe(true);
  });
});

describe('safeReturnPath — 오픈 리다이렉트 시도는 null (D-133)', () => {
  it.each([
    ['//evil.com', '프로토콜 상대 주소'],
    ['/\\evil.com', '역슬래시 — 브라우저가 `//` 로 읽는다'],
    ['\\\\evil.com', '역슬래시 두 개'],
    ['https://evil.com', '절대 주소'],
    ['http://evil.com/rooms/abc', '방 경로를 흉내 낸 절대 주소'],
    ['javascript:alert(1)', '스크립트 주소'],
    ['evil.com', '슬래시로 시작하지 않는 값'],
    ['rooms/abc', '상대 경로'],
    ['', '빈 문자열'],
    ['/\t/evil.com', '탭 — URL 파서가 지워 `//evil.com` 이 된다'],
    ['/\n/evil.com', '줄바꿈 — URL 파서가 지운다'],
  ])('%j (%s)', (from) => {
    expect(safeReturnPath(from)).toBeNull();
  });

  it('위 값 중 슬래시로 시작하는 것들은 실제로 사이트 밖으로 나간다 — 거절해야 하는 이유', () => {
    expect(staysOnSite('//evil.com')).toBe(false);
    expect(staysOnSite('/\\evil.com')).toBe(false);
    expect(staysOnSite('/\t/evil.com')).toBe(false);
  });
});

describe('safeReturnPath — 되돌이·비문자열은 null (D-133)', () => {
  it.each(['/login', '/register', '/login?next=1', '/register/', '/login#top', '/LOGIN'])(
    '로그인·가입 화면 %s 로는 돌아가지 않는다',
    (from) => {
      expect(safeReturnPath(from)).toBeNull();
    },
  );

  it.each([undefined, null, 42, {}, ['/rooms/abc']])('문자열이 아닌 %j 는 null', (from) => {
    expect(safeReturnPath(from)).toBeNull();
  });
});
