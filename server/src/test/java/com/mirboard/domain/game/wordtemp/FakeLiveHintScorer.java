package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.scoring.LiveHintScorer;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트용 가짜 채점기 — 네트워크 호출 0. 맡은 쌍을 적어 두고, 테스트가 {@link #deliver} 로 결과를 넣거나 {@link #answerWith} 로 미리 정해 둔
 * 답을 맡는 즉시 돌려준다. 결과는 운영 구현처럼 <b>한 번 생기면 계속 같은 값</b>이다.
 */
public final class FakeLiveHintScorer implements LiveHintScorer {

    public volatile boolean available = true;
    /** 맡은 쌍("정답/힌트")의 기록. */
    public final List<String> requests = new CopyOnWriteArrayList<>();
    /** 맡을 때 정답의 뜻풀이가 같이 왔는지 본다. */
    public final List<String> glosses = new CopyOnWriteArrayList<>();
    private final Map<String, LiveScore> results = new ConcurrentHashMap<>();
    private final Map<String, LiveScore> canned = new ConcurrentHashMap<>();

    private static String key(String answer, String hint) {
        return answer + "/" + hint;
    }

    /** 이 힌트는 맡는 즉시 이 답이 나온다(어느 정답이든). */
    public FakeLiveHintScorer answerWith(String hint, LiveScore score) {
        canned.put(hint, score);
        return this;
    }

    /** 채점이 끝났다 — 결과함에 넣는다. */
    public void deliver(String answer, String hint, LiveScore score) {
        results.put(key(answer, hint), score);
    }

    /** 결과함·기록을 잃는다(서버 재시작 흉내). */
    public void forget() {
        results.clear();
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void request(AnswerEntry answer, String hint) {
        requests.add(key(answer.word(), hint));
        glosses.add(answer.gloss());
        LiveScore known = results.get(key(answer.word(), hint));
        if (known instanceof LiveScore.Failed) {
            results.remove(key(answer.word(), hint));
        }
        LiveScore ready = canned.get(hint);
        if (ready != null) {
            results.put(key(answer.word(), hint), ready);
        }
    }

    @Override
    public Optional<LiveScore> poll(AnswerEntry answer, String hint) {
        return Optional.ofNullable(results.get(key(answer.word(), hint)));
    }
}
