import { ButtonHTMLAttributes } from 'react';

type Variant = 'default' | 'primary' | 'danger' | 'subtle';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

/**
 * 공용 버튼. variant 로 색상 톤만 분기. 크기/패딩은 글로벌 button 스타일 그대로 사용.
 *
 * <p>인라인 <button> 을 새로 깔지 말고 본 컴포넌트를 쓰면 토큰 변경 시 일관 갱신됨.
 */
export function Button({ variant = 'default', className, ...rest }: ButtonProps) {
  const variantClass = variant === 'default' ? '' : `btn-${variant}`;
  return (
    <button
      {...rest}
      className={[variantClass, className].filter(Boolean).join(' ').trim()}
    />
  );
}
