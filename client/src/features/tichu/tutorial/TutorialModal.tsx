import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { TICHU_TUTORIAL } from './tichuTutorial';

interface TutorialModalProps {
  open: boolean;
  onClose: () => void;
}

/**
 * A2 — 티츄 다단계 룰 튜토리얼. D-121 에서 마크업을 게임 중립 `TutorialDialog` 로 옮기고
 * 티츄 선언만 꽂는 래퍼가 됐다. 허브·대기실은 레지스트리(`tutorialFor`)를 쓰므로 이 래퍼는
 * 티츄 회귀 가드이자 티츄 게임판 진입점 후보로 남긴다.
 */
export function TutorialModal({ open, onClose }: TutorialModalProps) {
  return <TutorialDialog tutorial={TICHU_TUTORIAL} open={open} onClose={onClose} />;
}
