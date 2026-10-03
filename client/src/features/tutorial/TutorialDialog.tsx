import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import type { GameTutorial } from './types';

interface TutorialDialogProps {
  tutorial: GameTutorial;
  open: boolean;
  onClose: () => void;
}

/**
 * A2 → D-121 — 게임 중립 다단계 튜토리얼 다이얼로그. 단계 내용은 `GameTutorial` 로 주입받는다
 * (티츄 `TutorialModal` 의 마크업을 그대로 옮겼다).
 *
 * <p>스크롤은 본문(`.tutorial-body`)에만 준다 — 긴 단계에서도 제목과 이전/다음 푸터가 화면에
 * 고정된다.
 */
export function TutorialDialog({ tutorial, open, onClose }: TutorialDialogProps) {
  const [step, setStep] = useState(0);
  const total = tutorial.steps.length;
  const current = tutorial.steps[Math.min(step, total - 1)];
  const isLast = step >= total - 1;

  // 다시 열 때 항상 처음부터.
  useEffect(() => {
    if (open) setStep(0);
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="app-shell" style={{ maxWidth: 560 }}>
        <DialogHeader>
          <DialogTitle>{current.title}</DialogTitle>
          <DialogDescription className="sr-only">{tutorial.description}</DialogDescription>
        </DialogHeader>

        <div
          className={cn('tutorial-body max-h-[60dvh] overflow-y-auto', tutorial.bodyClassName)}
          style={{ minHeight: 180 }}
        >
          {current.body}
        </div>

        <DialogFooter className="gap-2" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <span style={{ fontSize: '0.85rem', opacity: 0.7 }} aria-hidden="true">
            {step + 1} / {total}
          </span>
          <span style={{ display: 'flex', gap: 8 }}>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={step === 0}
              onClick={() => setStep((s) => Math.max(0, s - 1))}
            >
              이전
            </Button>
            {isLast ? (
              <Button type="button" size="sm" onClick={onClose}>
                시작하기
              </Button>
            ) : (
              <Button type="button" size="sm" onClick={() => setStep((s) => Math.min(total - 1, s + 1))}>
                다음
              </Button>
            )}
          </span>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
