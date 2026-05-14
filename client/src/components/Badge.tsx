import { HTMLAttributes, ReactNode } from 'react';

type Tone = 'default' | 'success' | 'warning' | 'danger' | 'phoenix' | 'accent';

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: Tone;
  children: ReactNode;
}

/**
 * 작은 시각 표식. tone 으로 색상 분기. 인라인 <span className="badge"> 대신 사용.
 */
export function Badge({ tone = 'default', className, children, ...rest }: BadgeProps) {
  const toneClass = tone === 'default' ? 'badge' : `badge badge-${tone}`;
  return (
    <span {...rest} className={[toneClass, className].filter(Boolean).join(' ').trim()}>
      {children}
    </span>
  );
}
