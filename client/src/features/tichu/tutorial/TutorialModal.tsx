import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { TUTORIAL_STEPS } from './tutorialSteps';

interface TutorialModalProps {
  open: boolean;
  onClose: () => void;
}

/** A2 — 다단계 온보딩 룰 튜토리얼. 첫 로그인 1회 자동 + 헤더 도움말 버튼으로 재호출. */
export function TutorialModal({ open, onClose }: TutorialModalProps) {
  const [step, setStep] = useState(0);
  const total = TUTORIAL_STEPS.length;
  const current = TUTORIAL_STEPS[step];
  const isLast = step === total - 1;

  // 다시 열 때 항상 처음부터.
  useEffect(() => {
    if (open) setStep(0);
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="app-shell" style={{ maxWidth: 560 }}>
        <DialogHeader>
          <DialogTitle>{current.title}</DialogTitle>
        </DialogHeader>

        <div className="tutorial-body" style={{ minHeight: 180 }}>
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
