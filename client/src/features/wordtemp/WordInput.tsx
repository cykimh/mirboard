import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { normalizeWord } from './wordTempMath';

interface Props {
  /** 입력란 이름(보조기기). */
  label: string;
  placeholder: string;
  submitLabel: string;
  /** 입력 자체를 잠근다(채점 중·다 냄). */
  locked?: boolean;
  /** 보낸 것의 응답을 기다리는 중 — 버튼은 `aria-disabled`(포커스는 지킨다). */
  busy?: boolean;
  /** 낱말을 보낸다. 보냈으면 true(입력란을 비운다). */
  onSubmit: (text: string) => boolean;
  /** 버튼 옆에 더 놓을 것([패스]·[힌트 끝]). */
  children?: ReactNode;
}

/** 한글 낱말 최대 10자(룰 §5.1) — 공백·조합 여유를 두고 받는다. 길이 판정은 서버가 한다. */
const MAX_LENGTH = 16;

/**
 * 낱말 입력란 — 힌트·추리가 같이 쓴다(D-319).
 *
 * <p><b>한글 조합 중에는 보내지 않는다.</b> 조합 중의 Enter 는 글자를 확정하는 키라, 그때 보내면 "사ㄱ" 같은 반쪽 낱말이 나간다.
 * `isComposing`·조합 시작/끝 기록·`keyCode 229`(사파리는 조합 끝 뒤에 Enter keydown 을 229 로 보낸다) 셋 중 하나라도 걸리면 그 Enter 는
 * 버린다. 버튼으로 보낼 때 조합 중이면 먼저 입력란을 흐려 글자를 확정시킨 뒤 DOM 의 값을 읽는다(휴대폰 한글 자판은 마지막 글자가 늘 조합 중이다).
 *
 * <p>보내기 전 NFC·공백 제거만 한다(서버가 정본 — 한글 여부·길이·금지 낱말은 서버가 문구로 돌려준다). 버튼은 누를 때 입력란의 포커스를
 * 가져가지 않아 휴대폰 자판이 내려가지 않는다. 자동 완성·맞춤법 제안·자동 대문자는 끈다. 게임판 글자 선택 막기(D-292)는 입력란을 건드리지 않는다.
 */
export function WordInput({ label, placeholder, submitLabel, locked = false, busy = false, onSubmit, children }: Props) {
  const [value, setValue] = useState('');
  const composing = useRef(false);
  const input = useRef<HTMLInputElement>(null);

  const submit = (raw: string) => {
    if (locked || busy) return;
    const text = normalizeWord(raw);
    if (!text) return;
    if (onSubmit(text)) setValue('');
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter') return;
    // 조합 중의 Enter — 글자 확정용이다. 보내지 않는다(기본 동작은 그대로 둬 조합이 끝나게 한다).
    if (e.nativeEvent.isComposing || composing.current || e.keyCode === 229) return;
    e.preventDefault();
    submit(e.currentTarget.value);
  };

  const onButton = () => {
    const el = input.current;
    if (!el) return;
    if (composing.current) {
      // 조합을 끝낸다(흐리면 브라우저가 글자를 확정한다) — 그 뒤의 값이 사용자가 본 낱말이다.
      el.blur();
      composing.current = false;
      submit(el.value);
      el.focus();
      return;
    }
    submit(el.value);
  };

  return (
    <div className="wt-input-row">
      <input
        ref={input}
        className="wt-input"
        type="text"
        value={value}
        aria-label={label}
        placeholder={placeholder}
        disabled={locked}
        maxLength={MAX_LENGTH}
        lang="ko"
        inputMode="text"
        enterKeyHint="send"
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false}
        onChange={(e) => setValue(e.target.value)}
        onCompositionStart={() => {
          composing.current = true;
        }}
        onCompositionEnd={(e) => {
          composing.current = false;
          setValue(e.currentTarget.value);
        }}
        onKeyDown={onKeyDown}
      />
      <button
        type="button"
        className="wt-primary"
        disabled={locked}
        aria-disabled={busy || undefined}
        // 누를 때 입력란의 포커스를 가져가지 않는다 — 휴대폰 자판이 내려갔다 올라오지 않게.
        onPointerDown={(e) => e.preventDefault()}
        onClick={onButton}
      >
        {submitLabel}
      </button>
      {children}
    </div>
  );
}
