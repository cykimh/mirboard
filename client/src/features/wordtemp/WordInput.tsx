import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { WORD_MAX, checkWord, type WordCheck } from './wordTempMath';

interface Props {
  /** 입력란 이름(보조기기). */
  label: string;
  placeholder: string;
  submitLabel: string;
  /** 입력 자체를 잠근다(채점 중·다 냄·기준을 넘김). */
  locked?: boolean;
  /** 잠긴 까닭 — 입력란 아래 한 줄로 보여 준다("채점 중…"). */
  lockedReason?: string | null;
  /** 보낸 것의 응답을 기다리는 중 — 버튼은 `aria-disabled`(포커스는 지킨다). 모습은 꺼진 버튼과 같다. */
  busy?: boolean;
  /** 낱말을 보낸다. 보냈으면 true(입력란을 비운다). */
  onSubmit: (text: string) => boolean;
  /** 버튼 옆에 더 놓을 것([패스]·[그만 내기]). */
  children?: ReactNode;
}

/** 입력란이 받는 글자 수 — 낱말은 10자까지지만 사이에 넣은 공백·문장부호는 빼고 세므로 여유를 둔다. */
const MAX_LENGTH = 24;

/** 못 보내는 까닭 한 줄. 빈 값은 말하지 않는다(안내 글이 이미 있다). 쓰는 중의 자음·모음은 재촉하지 않는다. */
function whyNot(check: WordCheck, composing: boolean): string | null {
  switch (check.problem) {
    case 'long':
      return `${WORD_MAX}자까지 쓸 수 있어요`;
    case 'chars':
      return '한글만 쓸 수 있어요';
    case 'jamo':
      return composing ? null : '자음·모음만으로는 낼 수 없어요';
    default:
      return null;
  }
}

/**
 * 낱말 입력란 — 힌트·추리가 같이 쓴다(D-319).
 *
 * <p><b>버튼은 "지금 쓴 글이 지금 보낼 수 있을 때"에만 켜진다(D-323).</b> 잠기지 않았고, 보낸 것을 기다리는 중이 아니고, 다듬은 낱말이
 * 서버가 받는 꼴(완성형 한글 1~10자 — {@link checkWord}, 서버 `WordNormalizer` 와 같은 규칙)일 때다. 꺼져 있는 까닭은 입력란 아래 한 줄로
 * 말한다(잠긴 까닭·"보내는 중…"·"한글만 쓸 수 있어요"). 예전에는 빈 입력란에도 버튼이 켜져 보였고 눌러도 아무 일이 없었다. 낱말의 품사나
 * 고유 명사 여부는 화면이 가리지 않는다 — 서버가 받는 것은 화면도 막지 않는다.
 *
 * <p><b>한글 조합 중에는 Enter 로 보내지 않는다.</b> 조합 중의 Enter 는 글자를 확정하는 키라, 그때 보내면 "사ㄱ" 같은 반쪽 낱말이 나간다.
 * `isComposing`·조합 시작/끝 기록·`keyCode 229`(사파리는 조합 끝 뒤에 Enter keydown 을 229 로 보낸다) 셋 중 하나라도 걸리면 그 Enter 는
 * 버린다. 버튼으로 보낼 때 조합 중이면 먼저 입력란을 흐려 글자를 확정시킨 뒤 DOM 의 값을 읽는다(휴대폰 한글 자판은 마지막 글자가 늘 조합 중이다).
 * 버튼이 켜지는 판정은 조합 중인 글자까지 포함한 지금 값으로 한다 — 마지막 글자를 쓰는 순간 켜진다.
 *
 * <p>버튼은 누를 때 입력란의 포커스를 가져가지 않아 휴대폰 자판이 내려가지 않는다. 자동 완성·맞춤법 제안·자동 대문자는 끈다. 게임판 글자 선택
 * 막기(D-292)는 입력란을 건드리지 않는다.
 */
export function WordInput({
  label,
  placeholder,
  submitLabel,
  locked = false,
  lockedReason = null,
  busy = false,
  onSubmit,
  children,
}: Props) {
  const [value, setValue] = useState('');
  // 조합 중인가 — Enter 판정은 ref(이벤트 순서), 안내 줄은 state(다시 그리기)가 쓴다.
  const composing = useRef(false);
  const [composingNow, setComposingNow] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const check = checkWord(value);
  const ready = !locked && check.ok;
  const reason = locked ? lockedReason : busy ? '보내는 중…' : whyNot(check, composingNow);

  const submit = (raw: string) => {
    if (locked || busy) return;
    const now = checkWord(raw);
    if (!now.ok) return;
    if (onSubmit(now.text)) setValue('');
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
      setComposingNow(false);
      submit(el.value);
      el.focus();
      return;
    }
    submit(el.value);
  };

  return (
    <div className="wt-input-wrap">
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
            setComposingNow(true);
          }}
          onCompositionEnd={(e) => {
            composing.current = false;
            setComposingNow(false);
            setValue(e.currentTarget.value);
          }}
          onKeyDown={onKeyDown}
        />
        <button
          type="button"
          className="wt-primary"
          disabled={!ready}
          aria-disabled={busy || undefined}
          // 누를 때 입력란의 포커스를 가져가지 않는다 — 휴대폰 자판이 내려갔다 올라오지 않게.
          onPointerDown={(e) => e.preventDefault()}
          onClick={onButton}
        >
          {submitLabel}
        </button>
        {children}
      </div>
      {/* 버튼이 꺼져 있는 까닭 — 자리는 늘 잡아 둬 글이 생겨도 화면이 밀리지 않는다 */}
      <p className="wt-input-why" role="status">
        {reason ?? ''}
      </p>
    </div>
  );
}
