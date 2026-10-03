package com.mirboard.domain.lobby.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-117 — 게스트 체험 설정. {@code mirboard.guest.*}.
 *
 * @param enabled    킬스위치. false 면 생성이 403 GUEST_DISABLED (기본 true).
 * @param dailyCap   UTC 하루 전역 생성 상한 — 남용 방어가 아니라 <b>비용 상한</b>이다
 *                   (IP 당 한도는 레이트리밋 `guest` 버킷). 0 이하면 200.
 * @param retention  한 판도 끝내지 않은 게스트 행을 지우기까지의 보존 기간(기본 48h).
 *                   JWT 12h 보다 넉넉해야 진행 중인 신원을 지우지 않는다.
 * @param sweepBatch 정리 1회에 살펴볼 최대 행 수(기본 100).
 */
@ConfigurationProperties("mirboard.guest")
public record GuestProperties(Boolean enabled, int dailyCap, Duration retention, int sweepBatch) {

    public GuestProperties {
        if (enabled == null) enabled = true;
        if (dailyCap <= 0) dailyCap = 200;
        if (retention == null) retention = Duration.ofHours(48);
        if (sweepBatch <= 0) sweepBatch = 100;
    }
}
