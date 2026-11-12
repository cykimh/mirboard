import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Play, Trash2, Upload } from 'lucide-react';
import { soundsApi, type SoundClipInfo } from '@/api/sounds';
import { useAuthStore } from '@/features/auth/authStore';
import { useIsAdmin } from '@/features/auth/useIsAdmin';
import { VOICE_CLIPS, loadVoiceClips, playVoice, type VoiceKey } from '@/features/sound/voiceClips';
import { showToast } from '@/components/toast/toastStore';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription } from '@/components/ui/alert';

/** 서버 한도와 같다(`SoundClipService.MAX_BYTES`) — 목록 응답의 `maxBytes` 가 오면 그 값을 쓴다. */
const DEFAULT_MAX_BYTES = 500 * 1024;

function sizeText(bytes: number): string {
  return bytes < 1024 ? `${bytes}B` : `${Math.round(bytes / 1024)}KB`;
}

function typeText(contentType: string): string {
  return contentType.replace('audio/', '').replace('mpeg', 'mp3');
}

/**
 * D-265 — 관리 화면: 게임 음성 올리기. 키마다 지금 상태(올린 파일 / 기본 음성)·미리 듣기·올리기·지우기.
 * 관리자에게만 보이지만 화면 가드는 편의일 뿐이고, 권한은 서버 `/api/admin/sounds` 가 매번 판정한다.
 */
export function AdminSoundsPage() {
  const navigate = useNavigate();
  const token = useAuthStore((s) => s.token);
  const isAdmin = useIsAdmin();
  const [clips, setClips] = useState<Record<string, SoundClipInfo> | null>(null);
  const [maxBytes, setMaxBytes] = useState(DEFAULT_MAX_BYTES);
  const [error, setError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const inputs = useRef<Record<string, HTMLInputElement | null>>({});

  const reload = useCallback(async () => {
    if (!token) return;
    try {
      const res = await soundsApi.adminList(token);
      setClips(Object.fromEntries(res.clips.map((c) => [c.key, c])));
      setMaxBytes(res.maxBytes || DEFAULT_MAX_BYTES);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '목록을 받지 못했습니다');
    }
  }, [token]);

  useEffect(() => {
    if (isAdmin) void reload();
  }, [isAdmin, reload]);

  /** 올리거나 지운 뒤 — 관리 목록과 재생용 목록(버전)을 같이 새로 받는다. */
  const afterChange = async () => {
    await Promise.all([reload(), loadVoiceClips(true)]);
  };

  const onFile = async (key: VoiceKey, file: File | undefined) => {
    if (!file || !token) return;
    if (file.size > maxBytes) {
      showToast(`파일이 너무 커요(최대 ${sizeText(maxBytes)})`, 'error');
      return;
    }
    setBusyKey(key);
    try {
      await soundsApi.upload(token, key, file);
      await afterChange();
      showToast('올렸어요');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '올리지 못했어요', 'error');
    } finally {
      setBusyKey(null);
    }
  };

  const onDelete = async (key: VoiceKey, label: string) => {
    if (!token) return;
    if (!window.confirm(`"${label}" 음성을 지우고 기본 음성으로 돌릴까요?`)) return;
    setBusyKey(key);
    try {
      await soundsApi.remove(token, key);
      await afterChange();
      showToast('기본 음성으로 돌렸어요');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '지우지 못했어요', 'error');
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <div className="app-shell min-h-screen bg-background text-foreground">
      <div className="mx-auto flex max-w-2xl flex-col gap-6 px-4 py-6">
        <header className="flex items-center justify-between">
          <h1 className="text-2xl font-bold tracking-tight">게임 음성 관리</h1>
          <Button variant="outline" size="sm" onClick={() => navigate('/games')}>
            <ArrowLeft className="h-4 w-4" />
            메인으로
          </Button>
        </header>

        {isAdmin === false && (
          <Alert variant="destructive">
            <AlertDescription>관리자만 볼 수 있어요.</AlertDescription>
          </Alert>
        )}

        {isAdmin && (
          <>
            <p className="text-sm text-muted-foreground">
              게임 중에 나오는 음성을 직접 올려요. 올린 파일이 없으면 브라우저가 문구를 읽어 줍니다. mp3·ogg·wav·m4a·webm,
              파일 하나에 최대 {sizeText(maxBytes)}. 올리는 파일의 저작권은 올리는 사람이 책임져요.
            </p>
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <ul className="m-0 flex list-none flex-col gap-2 p-0" aria-label="음성 목록">
              {VOICE_CLIPS.map(({ key, label, phrase }) => {
                const clip = clips?.[key];
                const busy = busyKey === key;
                return (
                  <li
                    key={key}
                    data-testid={`sound-${key}`}
                    className="flex flex-col gap-2 rounded-lg border p-3 sm:flex-row sm:items-center"
                  >
                    <div className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="font-medium">{label}</span>
                      <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                        {clip ? (
                          <>
                            <Badge>올린 파일</Badge>
                            {typeText(clip.contentType)} · {sizeText(clip.byteSize)} ·{' '}
                            {new Date(clip.updatedAt).toLocaleString('ko-KR')}
                          </>
                        ) : (
                          <>
                            <Badge variant="secondary">기본 음성</Badge>
                            브라우저가 "{phrase}" 를 읽어요
                          </>
                        )}
                      </span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        aria-label={`${label} 듣기`}
                        onClick={() => playVoice(key, { force: true })}
                      >
                        <Play className="h-4 w-4" aria-hidden="true" />
                        듣기
                      </Button>
                      <input
                        ref={(el) => {
                          inputs.current[key] = el;
                        }}
                        type="file"
                        accept="audio/*,.mp3,.ogg,.wav,.m4a,.aac,.webm"
                        className="hidden"
                        aria-label={`${label} 파일`}
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          e.target.value = ''; // 같은 파일을 다시 골라도 반응하게.
                          void onFile(key, file);
                        }}
                      />
                      <Button
                        type="button"
                        size="sm"
                        disabled={busy}
                        aria-label={`${label} 올리기`}
                        onClick={() => inputs.current[key]?.click()}
                      >
                        <Upload className="h-4 w-4" aria-hidden="true" />
                        {clip ? '바꾸기' : '올리기'}
                      </Button>
                      {clip && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          aria-label={`${label} 지우기`}
                          onClick={() => void onDelete(key, label)}
                        >
                          <Trash2 className="h-4 w-4" aria-hidden="true" />
                          지우기
                        </Button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}
