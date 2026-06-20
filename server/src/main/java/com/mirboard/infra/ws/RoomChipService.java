package com.mirboard.infra.ws;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * D-82 — 방 단위 테이블 칩 운영. 게임 시작 시 전원 동일 칩으로 초기화(stake>0 방), 매치 종료
 * 마다 판돈을 승팀↔패팀으로 이동(제로섬, 패자 보유분 한도 올인)시키고 결과를 공개 토픽으로
 * 브로드캐스트한다(`CHIPS_SETTLED`). 칩은 계정이 아니라 `room:{id}:chips`(Redis)에만 존재.
 *
 * <p>infra 계층이라 lobby(RoomService/RoomChipStore)와 game(이벤트/Team)을 함께 오케스트레이션
 * 한다(도메인 경계 위반 아님 — domain.game.tichu 는 lobby 를 모름).
 */
@Component
public class RoomChipService {

    private static final Logger log = LoggerFactory.getLogger(RoomChipService.class);

    /** 방 게임 시작 시 전원에게 주는 동일 시작 칩(테이블 바이인). */
    public static final long STARTING_STACK = 1000;

    private final RoomChipStore store;
    private final RoomService rooms;
    private final BotUserRegistry bots;
    private final StompPublisher publisher;
    private final Clock clock;

    public RoomChipService(RoomChipStore store, RoomService rooms, BotUserRegistry bots,
                           StompPublisher publisher, Clock clock) {
        this.store = store;
        this.rooms = rooms;
        this.bots = bots;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 게임 시작(첫 매치/리매치) — stake>0 방은 칩 초기화(이미 있으면 유지). C3 에서 재바이인 추가. */
    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!TichuGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        Room room;
        try {
            room = rooms.getRoom(event.roomId());
        } catch (RoomNotFoundException e) {
            return;
        }
        if (room.stake() <= 0) {
            return;
        }
        store.initIfAbsent(event.roomId(), event.playerIds(), STARTING_STACK);
        // D-82 — 리매치 새 매치 시작 시 판돈 미만 보유자(빈털터리)는 무료 재바이인.
        store.rebuyBelow(event.roomId(), event.playerIds(), room.stake(), STARTING_STACK);
        broadcast(event.roomId(), store.stacks(event.roomId()), Map.of());
    }

    /** 매치 종료 — 판돈 정산(stake>0·사람 매치만). */
    @EventListener
    public void onMatchCompleted(TichuMatchCompleted event) {
        if (event.stake() <= 0) {
            return;
        }
        boolean hasBots = event.playerIds().stream().anyMatch(bots::isBot);
        if (hasBots) {
            return; // 봇 매치는 칩 정산 제외(파밍 방지) — 봇 방은 애초에 stake=0.
        }
        Map<Long, Long> stacks = store.stacks(event.roomId());
        if (stacks.isEmpty()) {
            return; // 방어 — 초기화 안 된 방.
        }

        List<Long> winners = new ArrayList<>();
        List<Long> losers = new ArrayList<>();
        for (int seat = 0; seat < event.playerIds().size(); seat++) {
            long uid = event.playerIds().get(seat);
            (Team.ofSeat(seat) == event.winningTeam() ? winners : losers).add(uid);
        }

        // 패자는 보유분 한도 내 판돈 차감→팟, 승자 균등 분배(나머지는 첫 승자). 제로섬·음수 불가.
        Map<Long, Long> deltas = new LinkedHashMap<>();
        long pot = 0;
        for (long loser : losers) {
            long pay = Math.min(event.stake(), stacks.getOrDefault(loser, 0L));
            deltas.put(loser, -pay);
            pot += pay;
        }
        long share = winners.isEmpty() ? 0 : pot / winners.size();
        long remainder = winners.isEmpty() ? 0 : pot % winners.size();
        for (int i = 0; i < winners.size(); i++) {
            deltas.put(winners.get(i), share + (i == 0 ? remainder : 0));
        }

        Map<Long, Long> next = new LinkedHashMap<>(stacks);
        deltas.forEach((uid, d) -> next.merge(uid, d, Long::sum));
        store.setStacks(event.roomId(), next);
        broadcast(event.roomId(), next, deltas);
        log.info("Chips settled: room={} stake={} pot={} deltas={}",
                event.roomId(), event.stake(), pot, deltas);
    }

    private void broadcast(String roomId, Map<Long, Long> stacks, Map<Long, Long> deltas) {
        publisher.publishToTopic("/topic/room/" + roomId,
                StompEnvelope.of("CHIPS_SETTLED", new ChipsPayload(stacks, deltas), clock));
    }

    /** userId→칩 맵은 Jackson 이 {"17":1000} 형태로 직렬화(키 문자열화). */
    public record ChipsPayload(Map<Long, Long> stacks, Map<Long, Long> deltas) {
    }
}
