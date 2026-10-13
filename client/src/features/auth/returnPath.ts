/**
 * D-133 — 로그인 뒤 돌아갈 주소(`state.from`)를 사이트 안 경로로만 받는다.
 *
 * <p>보호 경로(`ProtectedRoute`)가 `/login` 으로 보낼 때 가려던 경로를 싣고, 로그인·게스트 입장·가입 뒤 이
 * 함수가 통과시킨 값으로만 이동한다(아니면 호출 측이 `/games`). 거절하는 것:
 * <ul>
 *   <li>문자열이 아니거나 `/` 로 시작하지 않는 값 — 절대 주소(`https://…`)·`javascript:`·상대 경로.</li>
 *   <li>`//`·`/\` 로 시작하는 값 — 브라우저는 역슬래시를 슬래시로 읽어 둘 다 다른 사이트 주소가 된다.</li>
 *   <li>제어 문자 — URL 파서가 탭·줄바꿈을 지워 `/\t/evil.com` 이 `//evil.com` 이 된다.</li>
 *   <li>로그인·가입 화면 — 되돌이(라우트는 대소문자를 가리지 않는다).</li>
 * </ul>
 * 인코딩된 `/%2F%2Fevil.com` 은 사이트 안 경로 그대로라 받는다(라우터가 없는 경로로 보고 허브로 보낸다).
 */
const AUTH_PATHS: ReadonlySet<string> = new Set(['/login', '/register']);

// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

export function safeReturnPath(from: unknown): string | null {
  if (typeof from !== 'string' || !from.startsWith('/')) return null;
  if (from.startsWith('//') || from.startsWith('/\\')) return null;
  if (CONTROL_CHARS.test(from)) return null;
  const path = from.replace(/[?#].*$/, '').replace(/\/+$/, '').toLowerCase();
  if (AUTH_PATHS.has(path)) return null;
  return from;
}
