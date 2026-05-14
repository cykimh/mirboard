import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useAuthStore } from '@/features/auth/authStore';

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
    <main className="auth-page">
      <h1>회원가입</h1>
      <p className="hint">
        Mirboard 는 아이디와 비밀번호만 저장합니다. 이메일/전화번호는 받지 않습니다.
      </p>
      <form onSubmit={handleSubmit}>
        <label>
          아이디 (영문/숫자/언더스코어 3~20자)
          <input
            type="text"
            value={username}
            pattern="^[A-Za-z0-9_]{3,20}$"
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </label>
        <label>
          비밀번호 (8~64자)
          <input
            type="password"
            value={password}
            minLength={8}
            maxLength={64}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? '처리 중...' : '회원가입 후 로그인'}
        </button>
      </form>
      <p>
        이미 계정이 있나요? <Link to="/login">로그인</Link>
      </p>
    </main>
  );
}
