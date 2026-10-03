import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useAuthStore } from '@/features/auth/authStore';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Separator } from '@/components/ui/separator';
import { t } from '@/i18n/messages';

/**
 * D-117 — 게스트 생성 실패 안내. 막다른 길이 되지 않게 둘 다 회원가입으로 잇는다.
 * 429 는 이 IP(IPv6 /64)의 하루 한도, 503/403 은 전역 상한·킬스위치(서버 사정).
 */
function guestErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.status === 429) {
    return t('auth.guest.error.rateLimited');
  }
  return t('auth.guest.error.unavailable');
}

export function LoginPage() {
  const login = useAuthStore((s) => s.login);
  const loginAsGuest = useAuthStore((s) => s.loginAsGuest);
  // D-122 — 이미 로그인한 사람에게는 로그인 화면을 보이지 않는다(아래 리다이렉트).
  const signedIn = useAuthStore(
    (s) => !!s.token && !!s.user && (s.expiresAt == null || s.expiresAt > Date.now()),
  );
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [guestError, setGuestError] = useState<string | null>(null);
  const [guestSubmitting, setGuestSubmitting] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password);
      // D-122 — replace: 뒤로가기로 로그인 화면에 돌아오지 않게.
      navigate('/games', { replace: true });
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '로그인 실패';
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleGuest() {
    setGuestError(null);
    setGuestSubmitting(true);
    try {
      await loginAsGuest();
      // D-122 — replace: 뒤로가기로 로그인 화면에 돌아오지 않게.
      navigate('/games', { replace: true });
    } catch (err) {
      setGuestError(guestErrorMessage(err));
    } finally {
      setGuestSubmitting(false);
    }
  }

  // D-122 — 로그인 상태로 /login 에 오면(게스트 입장 뒤 브라우저 '뒤로' 등) 허브로 보낸다.
  // 여기서 게스트 버튼을 다시 누르면 확인 없이 새 게스트가 발급돼, 비밀번호가 없는 이전 게스트
  // 신원을 영영 잃었다 — 허브 로그아웃에만 건 확인(D-117)을 우회하는 경로였다. 만료된 토큰과
  // 사용자 정보 없는 토큰은 보내지 않는다: 다시 로그인할 길을 막거나, 허브의 /login 가드
  // (`!token || !user`)와 서로 튕기는 왕복이 된다.
  if (signedIn) return <Navigate to="/games" replace />;

  return (
    <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">Mirboard 로그인</CardTitle>
          <CardDescription>미르보드카페에 오신 것을 환영합니다.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="login-username">아이디</Label>
              <Input
                id="login-username"
                type="text"
                value={username}
                autoComplete="username"
                onChange={(e) => setUsername(e.target.value)}
                required
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="login-password">비밀번호</Label>
              <Input
                id="login-password"
                type="password"
                value={password}
                autoComplete="current-password"
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <Button type="submit" disabled={submitting || guestSubmitting}>
              {submitting ? '로그인 중...' : '로그인'}
            </Button>
          </form>

          {/* D-117 — 가입 없는 체험. 처음 온 리뷰어가 가입 화면에서 이탈하지 않게. */}
          <div className="my-4 flex items-center gap-3 text-xs text-muted-foreground">
            <Separator className="flex-1" />
            <span>{t('auth.guest.or')}</span>
            <Separator className="flex-1" />
          </div>
          <Button
            type="button"
            variant="outline"
            className="w-full"
            onClick={handleGuest}
            disabled={guestSubmitting || submitting}
          >
            {guestSubmitting ? t('auth.guest.creating') : t('auth.guest.cta')}
          </Button>
          <p className="mt-2 text-center text-xs text-muted-foreground">
            {t('auth.guest.caption')}
          </p>
          {guestError && (
            <Alert className="mt-3">
              <AlertDescription className="flex flex-col items-start gap-2">
                <span>{guestError}</span>
                <Button asChild size="sm">
                  <Link to="/register">{t('auth.guest.signupCta')}</Link>
                </Button>
              </AlertDescription>
            </Alert>
          )}

          <p className="mt-4 text-center text-sm text-muted-foreground">
            계정이 없나요?{' '}
            <Link
              to="/register"
              className="text-primary underline-offset-4 hover:underline"
            >
              회원가입
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
