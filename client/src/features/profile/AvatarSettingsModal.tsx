import { useRef, useState } from 'react';
import { avatarApi, avatarSrc } from '@/api/avatar';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

interface AvatarSettingsModalProps {
  open: boolean;
  onClose: () => void;
  token: string;
  userId: number;
  username: string;
  /** 업로드/삭제 후 부모가 캐시 무력화(version 증가)에 사용. */
  onChanged: () => void;
}

/**
 * 프로필 아바타 설정(D-80) — 이미지 업로드(PNG/JPEG, 서버에서 128px 로 재인코딩)
 * 또는 삭제(이모지 디폴트로 복귀). 본인만.
 */
export function AvatarSettingsModal({
  open,
  onClose,
  token,
  userId,
  username,
  onChanged,
}: AvatarSettingsModalProps) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  async function handleUpload(file: File) {
    setBusy(true);
    setError(null);
    try {
      await avatarApi.upload(token, file);
      setVersion((v) => v + 1);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function handleRemove() {
    setBusy(true);
    setError(null);
    try {
      await avatarApi.remove(token);
      setVersion((v) => v + 1);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="app-shell sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>프로필 아바타</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col items-center gap-4 py-2">
          <Avatar className="h-24 w-24">
            <AvatarImage src={avatarSrc(userId, version || undefined)} alt="" />
            <AvatarFallback>{username.slice(0, 2).toUpperCase()}</AvatarFallback>
          </Avatar>
          <p className="text-xs text-muted-foreground text-center">
            PNG/JPEG 이미지를 올리면 128×128 로 저장됩니다. 설정하지 않으면 기본 동물
            아이콘이 표시됩니다.
          </p>
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
        </div>

        <input
          ref={fileRef}
          type="file"
          accept="image/png,image/jpeg"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) handleUpload(f);
            e.target.value = '';
          }}
        />

        <DialogFooter className="flex-col gap-2 sm:flex-row sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            disabled={busy}
            onClick={handleRemove}
          >
            기본으로 되돌리기
          </Button>
          <Button
            type="button"
            disabled={busy}
            onClick={() => fileRef.current?.click()}
          >
            이미지 업로드
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
