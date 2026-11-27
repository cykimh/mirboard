package com.mirboard.domain.game.wordtemp.scoring;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.StructuredContentBlock;
import com.anthropic.models.beta.messages.StructuredMessage;
import com.anthropic.models.beta.messages.StructuredMessageCreateParams;
import com.anthropic.models.beta.messages.StructuredOutputConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공식 Anthropic Java SDK 로 한 쌍을 채점한다(D-312·D-317, 설계서 §5.5).
 *
 * <ul>
 *   <li><b>보내는 것은 낱말뿐</b> — 시스템 = 채점 기준문(고정), 사용자 = {@code {"answer","gloss","hint"}} JSON 한 덩이. 사용자·방·좌석
 *       식별자는 이 클래스에 오지도 않는다. 힌트는 룰 §5.1(완성형 한글 1~10자)을 통과한 것만 온다.</li>
 *   <li>구조화 출력 — 스키마는 {@link HintVerdict} 에서 SDK 가 만든다. {@code output_config.effort: low}. thinking 은 보내지 않는다
 *       (Sonnet 5.5·Opus 5.5 는 끌 수 없다 — 깊이는 effort 로만).</li>
 *   <li>거절 대비 서버 쪽 fallbacks({@code "default"} 꼴, 베타 {@code server-side-fallback-2026-07-01}). 그래도
 *       {@code stop_reason} 이 {@code refusal} 이면 실패(무효·개수 반환)다 — 내용을 읽기 전에 먼저 본다.</li>
 *   <li>SDK 시간 제한 {@code timeout}(기본 5초)·재시도 0 — 엔진 쪽 기다림 상한 8초 안에 끝나야 한다.</li>
 *   <li>클라이언트는 <b>처음 부를 때</b> 만든다 — 실시간 채점을 쓰지 않는 서버는 SDK 클래스를 올리지 않는다(512MB 머신).</li>
 * </ul>
 */
public final class ClaudeHintJudge implements HintJudge {

    /** thinking 포함. 답은 수십 토큰이라 넉넉하다 — 그래도 잘리면({@code max_tokens}) 실패 처리한다. */
    private static final long MAX_TOKENS = 2_000L;

    private final String apiKey;
    private final String model;
    private final String prompt;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();
    private volatile AnthropicClient client;

    /**
     * @param promptVersion 채점 기준문 자원 이름({@code wordtemp/<promptVersion>.md}) — 캐시 키의 일부라 기준문을 바꾸면 버전을 올린다
     */
    public ClaudeHintJudge(String apiKey, String model, String promptVersion, Duration timeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.prompt = readPrompt(promptVersion);
        this.timeout = timeout;
    }

    /** 구조화 출력의 꼴. 필드 이름이 그대로 스키마의 키다. */
    public static final class HintVerdict {

        @JsonProperty("isValidNoun")
        @JsonPropertyDescription("힌트가 기준문의 뜻으로 쓸 수 있는 낱말(명사 — 고유명사 포함)인가. 쓸 수 없는 힌트면 false")
        public boolean isValidNoun;

        @JsonProperty("similarityScore")
        @JsonPropertyDescription("정답과 힌트의 가까움, 정수 0~100. 쓸 수 없는 힌트면 0")
        public int similarityScore;

        @JsonProperty("reason")
        @JsonPropertyDescription("한 줄 까닭")
        public String reason;
    }

    @Override
    public Verdict judge(AnswerEntry answer, String hint) {
        StructuredMessageCreateParams<HintVerdict> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .outputConfig(StructuredOutputConfig.<HintVerdict>builder()
                        .format(HintVerdict.class)
                        .effort(BetaOutputConfig.Effort.LOW)
                        .build())
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacksDefault()
                .system(prompt)
                .addUserMessage(userMessage(answer, hint))
                .build();
        StructuredMessage<HintVerdict> message;
        try {
            message = client().beta().messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException | BadRequestException | NotFoundException e) {
            // 키가 틀렸거나 요청 꼴·모델 이름이 틀렸다 — 다시 불러도 같다.
            throw new JudgeException(true, "live scoring rejected (" + e.statusCode() + ")", e);
        } catch (RateLimitException | InternalServerException e) {
            throw new JudgeException(false, "live scoring busy (" + e.statusCode() + ")", e);
        } catch (AnthropicServiceException e) {
            throw new JudgeException(false, "live scoring failed (" + e.statusCode() + ")", e);
        } catch (AnthropicIoException e) {
            throw new JudgeException(false, "live scoring connection failed or timed out", e);
        } catch (AnthropicException e) {
            throw new JudgeException(false, "live scoring response unreadable", e);
        }
        BetaStopReason stop = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            throw new JudgeException(false, "live scoring refused");
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            throw new JudgeException(false, "live scoring truncated");
        }
        try {
            for (StructuredContentBlock<HintVerdict> block : message.content()) {
                if (block.isText()) {
                    HintVerdict verdict = block.asText().text();
                    return new Verdict(verdict.isValidNoun, verdict.similarityScore, verdict.reason);
                }
            }
        } catch (AnthropicException e) {
            throw new JudgeException(false, "live scoring answer did not match the schema", e);
        }
        throw new JudgeException(false, "live scoring returned no answer");
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(0).build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** 낱말은 데이터로만 간다 — JSON 문자열 값으로 싣는다. */
    private String userMessage(AnswerEntry answer, String hint) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("answer", answer.word());
        body.put("gloss", answer.gloss());
        body.put("hint", hint);
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new JudgeException(false, "could not build the scoring request", e);
        }
    }

    static String readPrompt(String promptVersion) {
        String resource = "wordtemp/" + promptVersion + ".md";
        try (InputStream in = ClaudeHintJudge.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Scoring prompt not found: " + resource
                        + " (mirboard.wordtemp.live-scoring.prompt-version)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read scoring prompt " + resource, e);
        }
    }
}
