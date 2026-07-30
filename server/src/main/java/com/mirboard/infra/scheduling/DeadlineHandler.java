package com.mirboard.infra.scheduling;

/**
 * D-96 — 만료된 데드라인 하나를 처리한다. 구현체를 {@code @Component} 로 등록하면
 * {@link DeadlinePoller} 가 자동 수집해 자기 {@link #kind()} 큐를 폴링해준다.
 *
 * <p>구현 시 지켜야 할 것:
 * <ul>
 *   <li>{@link #handle} 이 받은 member 는 <b>이 인스턴스가 단독 소유</b>한다(원자 pop).
 *       처리하지 않고 버리면 그 타이머는 영영 사라진다.</li>
 *   <li>pop 과 실제 처리 사이에 다른 인스턴스가 상태를 바꿨을 수 있다 —
 *       <b>스스로 유효성을 재확인</b>해야 한다(예: generation 비교).</li>
 *   <li>예외를 던져도 폴러가 삼키고 다음 항목으로 넘어간다. 재시도가 필요하면
 *       스스로 다시 schedule 할 것.</li>
 * </ul>
 */
public interface DeadlineHandler {

    /** 이 핸들러가 담당하는 큐 이름 (`deadlines:{kind}`). */
    String kind();

    /** 만료된 항목 하나 처리. */
    void handle(String member);
}
