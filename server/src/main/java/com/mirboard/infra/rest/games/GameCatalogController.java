package com.mirboard.infra.rest.games;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameNotFoundException;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameCatalogController {

    private final GameRegistry registry;

    public GameCatalogController(GameRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public CatalogResponse list() {
        return new CatalogResponse(registry.catalog().stream().map(GameSummary::of).toList());
    }

    @GetMapping("/{id}")
    public GameSummary get(@PathVariable String id) {
        return GameSummary.of(
                Optional.ofNullable(id)
                        .flatMap(registry::find)
                        .filter(d -> d.status() != GameStatus.DISABLED)
                        .orElseThrow(() -> new GameNotFoundException(id)));
    }

    public record CatalogResponse(List<GameSummary> games) {
    }

    /**
     * D-106 — `supportedRoomOptions` 는 방 만들기 UI 가 무엇을 노출할지 정하는 근거다.
     * 게임이 안 쓰는 설정을 화면에 띄우지 않기 위한 것이며, 서버도 같은 집합으로 검증한다.
     *
     * <p>D-130 — `defaultPlayers`·`defaultTurnSeconds` 는 방 만들기 모달의 처음 선택이다(사용자는 바꿀 수 있다). D-154 —
     * `turnSecondsChoices` 는 모달의 턴 제한 선택지다.
     */
    public record GameSummary(
            String id,
            String displayName,
            String shortDescription,
            int minPlayers,
            int maxPlayers,
            GameStatus status,
            List<RoomOption> supportedRoomOptions,
            int defaultPlayers,
            int defaultTurnSeconds,
            List<Integer> turnSecondsChoices,
            List<RoomOptionChoices> roomOptionChoices) {

        static GameSummary of(GameDefinition d) {
            return new GameSummary(
                    d.id(),
                    d.displayName(),
                    d.shortDescription(),
                    d.minPlayers(),
                    d.maxPlayers(),
                    d.status(),
                    // enum 선언 순서로 고정 — 응답이 실행마다 흔들리지 않게.
                    d.supportedRoomOptions().stream().sorted().toList(),
                    d.defaultPlayers(),
                    d.defaultTurnSeconds(),
                    d.turnSecondsChoices(),
                    // D-176 — 선언한 맵 옵션의 선택지·기본값. 대기실이 옵션 이름만 보고 줄을 그릴 수 있게(게임별 화면 코드 없이).
                    d.supportedRoomOptions().stream().sorted().filter(RoomOption::valued)
                            .map(o -> new RoomOptionChoices(o, o.choices(), o.defaultValue()))
                            .toList());
        }
    }

    /** D-176 — 값을 가진 방 옵션 하나의 선택지(선언 순서)와 기본값. */
    public record RoomOptionChoices(RoomOption option, List<String> values, String defaultValue) {
    }
}
