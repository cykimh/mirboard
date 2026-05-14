import { CSSProperties, HTMLAttributes, ReactNode } from 'react';

interface StackProps extends HTMLAttributes<HTMLDivElement> {
  /** 자식 사이 간격 — space-* 토큰 인덱스 (1=4px, 2=8px, 3=12px, 4=16px, 5=24px, 6=32px). */
  gap?: 1 | 2 | 3 | 4 | 5 | 6;
  /** 방향. 기본 column. */
  direction?: 'row' | 'column';
  /** flex 정렬. */
  align?: CSSProperties['alignItems'];
  justify?: CSSProperties['justifyContent'];
  children?: ReactNode;
}

/**
 * Flex 레이아웃 유틸. 페이지/카드 내부의 vertical/horizontal stacking 을 인라인 style 없이.
 */
export function Stack({
  gap = 3,
  direction = 'column',
  align,
  justify,
  style,
  className,
  children,
  ...rest
}: StackProps) {
  return (
    <div
      {...rest}
      className={['stack', className].filter(Boolean).join(' ').trim()}
      style={{
        display: 'flex',
        flexDirection: direction,
        gap: `var(--space-${gap})`,
        alignItems: align,
        justifyContent: justify,
        ...style,
      }}
    >
      {children}
    </div>
  );
}
