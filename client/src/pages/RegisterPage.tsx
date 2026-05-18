import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
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

export function RegisterPage() {
  const register = useAuthStore((s) => s.register);
  const login = useAuthStore((s) => s.login);
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(username, password);
      await login(username, password);
      navigate('/games');
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '회원가입 실패';
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">회원가입</CardTitle>
          <CardDescription>
            Mirboard 는 아이디와 비밀번호만 저장합니다. 이메일/전화번호는
            받지 않습니다.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="reg-username">
                아이디 (영문/숫자/언더스코어 3~20자)
              </Label>
              <Input
                id="reg-username"
                type="text"
                value={username}
                pattern="^[A-Za-z0-9_]{3,20}$"
                onChange={(e) => setUsername(e.target.value)}
                required
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="reg-password">비밀번호 (8~64자)</Label>
              <Input
                id="reg-password"
                type="password"
                value={password}
                minLength={8}
                maxLength={64}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <Button type="submit" disabled={submitting}>
              {submitting ? '처리 중...' : '회원가입 후 로그인'}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            이미 계정이 있나요?{' '}
            <Link
              to="/login"
              className="text-primary underline-offset-4 hover:underline"
            >
              로그인
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
