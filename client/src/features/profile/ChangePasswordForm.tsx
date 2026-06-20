import { useState } from 'react';
import { ApiError } from '@/api/client';
import { meApi } from '@/api/me';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Alert, AlertDescription } from '@/components/ui/alert';

/** D-85 — 비밀번호 변경 폼. 클라 검증(8~64자·확인 일치) 후 PUT /api/me/password. */
export function ChangePasswordForm({ token }: { token: string }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setDone(false);
    if (next.length < 8 || next.length > 64) {
      setError('새 비밀번호는 8~64자여야 합니다.');
      return;
    }
    if (next !== confirm) {
      setError('새 비밀번호 확인이 일치하지 않습니다.');
      return;
    }
    setBusy(true);
    try {
      await meApi.changePassword(token, current, next);
      setDone(true);
      setCurrent('');
      setNext('');
      setConfirm('');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'BAD_CREDENTIALS') {
        setError('현재 비밀번호가 일치하지 않습니다.');
      } else if (err instanceof ApiError && err.code === 'INVALID_INPUT') {
        setError('새 비밀번호는 8~64자여야 합니다.');
      } else {
        setError('비밀번호 변경에 실패했습니다.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3">
      <Input
        type="password"
        aria-label="현재 비밀번호"
        placeholder="현재 비밀번호"
        value={current}
        onChange={(e) => setCurrent(e.target.value)}
        autoComplete="current-password"
      />
      <Input
        type="password"
        aria-label="새 비밀번호"
        placeholder="새 비밀번호 (8~64자)"
        value={next}
        onChange={(e) => setNext(e.target.value)}
        autoComplete="new-password"
      />
      <Input
        type="password"
        aria-label="새 비밀번호 확인"
        placeholder="새 비밀번호 확인"
        value={confirm}
        onChange={(e) => setConfirm(e.target.value)}
        autoComplete="new-password"
      />
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {done && (
        <Alert>
          <AlertDescription>비밀번호가 변경되었습니다.</AlertDescription>
        </Alert>
      )}
      <Button type="submit" disabled={busy}>
        비밀번호 변경
      </Button>
    </form>
  );
}
