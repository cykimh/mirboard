import { InputHTMLAttributes, forwardRef } from 'react';

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
}

/**
 * 공용 텍스트 입력. label prop 을 주면 stacked label + input 컬럼을 반환.
 * label 이 없으면 raw <input> 만.
 */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, className, ...rest },
  ref,
) {
  if (!label) {
    return <input ref={ref} {...rest} className={className} />;
  }
  return (
    <label className="input-field">
      <span className="input-label">{label}</span>
      <input ref={ref} {...rest} className={className} />
    </label>
  );
});
