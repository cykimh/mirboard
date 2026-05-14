import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useAuthStore } from '@/features/auth/authStore';
import { Button } from '@/components/Button';
import { Input } from '@/components/Input';
import { Stack } from '@/components/Stack';

export function LoginPage() {
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
      await login(username, password);
      navigate('/games');
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '로그인 실패';
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <h1>Mirboard 로그인</h1>
      <form onSubmit={handleSubmit}>
        <Stack gap={4}>
          <Input
            label="아이디"
            type="text"
            value={username}
            autoComplete="username"
            onChange={(e) => setUsername(e.target.value)}
            required
          />
          <Input
            label="비밀번호"
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {error && <p className="error">{error}</p>}
          <Button type="submit" variant="primary" disabled={submitting}>
            {submitting ? '로그인 중...' : '로그인'}
          </Button>
        </Stack>
      </form>
      <p>
        계정이 없나요? <Link to="/register">회원가입</Link>
      </p>
    </main>
  );
}
