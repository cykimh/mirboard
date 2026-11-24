import { useState } from 'react';
import { ApiError } from '@/api/client';
import { meApi } from '@/api/me';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Alert, AlertDescription } from '@/components/ui/alert';

/**
 * D-85 — 비밀번호 변경 폼. 클라 검증(4~64자 — D-156·확인 일치) 후 PUT /api/me/password.
 * D-304 — 현재 비밀번호는 묻지 않는다(새 비밀번호 + 확인만).
 */
export function ChangePasswordForm({ token }: { token: string }) {
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setDone(false);
    if (next.length < 4 || next.length > 64) {
      setError('새 비밀번호는 4자 이상이어야 합니다.');
      return;
    }
    if (next !== confirm) {
      setError('새 비밀번호 확인이 일치하지 않습니다.');
      return;
    }
    setBusy(true);
    try {
      await meApi.changePassword(token, next);
      setDone(true);
      setNext('');
      setConfirm('');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'INVALID_INPUT') {
        setError('새 비밀번호는 4자 이상이어야 합니다.');
      } else {
        setError('비밀번호 변경에 실패했습니다.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-2">
      <div className="grid gap-2 sm:grid-cols-2">
        <Input
          type="password"
          aria-label="새 비밀번호"
          placeholder="새 비밀번호 (4자 이상)"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          autoComplete="new-password"
          className="h-10"
        />
        <Input
          type="password"
          aria-label="새 비밀번호 확인"
          placeholder="새 비밀번호 확인"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          autoComplete="new-password"
          className="h-10"
        />
      </div>
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
      <Button type="submit" disabled={busy} className="h-10">
        비밀번호 변경
      </Button>
    </form>
  );
}
