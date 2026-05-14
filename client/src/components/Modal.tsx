import { ReactNode } from 'react';

interface ModalProps {
  open: boolean;
  title?: string;
  body?: ReactNode;
  /** 본문 영역 — body 와 별개로 추가 콘텐츠 슬롯이 필요할 때. */
  children?: ReactNode;
  /** 하단 액션 영역 (보통 Button 들). */
  actions?: ReactNode;
}

/**
 * 공용 모달 — 백드롭 + 카드 + 액션 푸터. MakeWishModal / GiveDragonTrickModal 등
 * 도메인 모달이 본 컴포넌트를 감싸 쓰면 일관 스타일.
 *
 * <p>open=false 면 null 반환. 포커스 트랩/ESC 닫기는 본 청크 범위 밖.
 */
export function Modal({ open, title, body, children, actions }: ModalProps) {
  if (!open) return null;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal">
        {title && <h3>{title}</h3>}
        {body && <p className="modal-body">{body}</p>}
        {children}
        {actions && <div className="modal-actions">{actions}</div>}
      </div>
    </div>
  );
}
