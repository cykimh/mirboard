interface Props {
  handSize: number;
  /** 이미 제출한 내 예측 (제출 후에는 변경 불가 — §5). */
  myBid: number | null;
  /** 아직 제출하지 않은 좌석 수 — 대기 상황 안내. */
  pendingCount: number;
  onPlaceBid: (bid: number) => void;
}

/**
 * 승수 예측 입력 (§5). 상한이 **손패 장수**라는 게 요점이다 — 라운드 번호가 아니다(§13-⑪).
 * 8인 라운드 9·10 은 손패가 8장이라 9·10 을 고를 수 없다.
 *
 * <p>제출 후에는 값을 보여주되 다시 누를 수 없다. 남의 예측은 이 패널에 절대 나오지
 * 않는다 — 전원 제출 전까지 비공개다(스토어가 값을 아예 담지 않는다).
 */
export function BidPanel({ handSize, myBid, pendingCount, onPlaceBid }: Props) {
  const options = Array.from({ length: handSize + 1 }, (_, i) => i);
  const submitted = myBid !== null;

  return (
    <section className="sk-bid" aria-label="승수 예측">
      <div className="sk-bid-head">
        <strong>몇 트릭을 이길까요?</strong>
        <span className="sk-bid-hint">
          {submitted
            ? `제출 완료 — 나머지 ${pendingCount}명 대기 중`
            : `0 ~ ${handSize} 중 선택 (제출 후 변경 불가)`}
        </span>
      </div>

      <div className="sk-bid-options">
        {options.map((n) => (
          <button
            key={n}
            type="button"
            className={`sk-bid-option${myBid === n ? ' selected' : ''}`}
            disabled={submitted}
            aria-pressed={myBid === n}
            onClick={() => onPlaceBid(n)}
          >
            {n}
          </button>
        ))}
      </div>

      {submitted && (
        <p className="sk-bid-note">
          전원이 제출하면 예측이 동시에 공개됩니다.
        </p>
      )}
    </section>
  );
}
