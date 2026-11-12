package com.mirboard.infra.rest.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.sound.SoundClipRepository;
import com.mirboard.domain.sound.SoundClipService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-265 — 음성 클립: 어드민만 올리고 지운다, 허용 키·크기·형식 검증, 올리기 → 공개 재생 → 지우기 왕복, ETag.
 * 풀 컨텍스트 기동이 V20 적용 + Hibernate 스키마 대조(BYTEA 매핑)도 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=sound-clip-controller-test-secret-32-bytes-or-more",
        // 올리기 버킷(EXPENSIVE_WRITE)이 테스트 여러 개를 막지 않게.
        "mirboard.ratelimit.enabled=false"
})
class SoundClipControllerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    private static final String KEY = "onecard.call";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired AdminRoleRepository adminRoleRepo;
    @Autowired SoundClipRepository clipRepo;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    @BeforeEach
    void clean() {
        clipRepo.deleteAll();
    }

    private User newUser(boolean admin) {
        User u = userRepo.save(User.create("snd" + SEQ.incrementAndGet(),
                passwordEncoder.encode("validpass1"), clock));
        if (admin) {
            adminRoleRepo.save(new AdminRole(u.getId(), clock.instant()));
        }
        return u;
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.issue(u.getId(), u.getUsername()).token();
    }

    /** 앞머리가 mp3(ID3) 인 가짜 파일. */
    private static byte[] mp3(int size) {
        byte[] data = new byte[size];
        data[0] = 'I';
        data[1] = 'D';
        data[2] = '3';
        return data;
    }

    private RequestBuilder upload(String key, byte[] data, String declaredType, User as) {
        var req = multipart(HttpMethod.PUT, "/api/admin/sounds/" + key)
                .file(new MockMultipartFile("file", "clip.bin", declaredType, data));
        if (as != null) {
            req.header("Authorization", bearer(as));
        }
        return req;
    }

    @Test
    void non_admin_cannot_list_upload_or_delete() throws Exception {
        User user = newUser(false);

        mockMvc.perform(get("/api/admin/sounds").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        mockMvc.perform(upload(KEY, mp3(64), "audio/mpeg", user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        mockMvc.perform(delete("/api/admin/sounds/" + KEY).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        // 토큰 없음은 401.
        mockMvc.perform(upload(KEY, mp3(64), "audio/mpeg", null))
                .andExpect(status().isUnauthorized());

        assertThat(clipRepo.count()).isZero();
    }

    @Test
    void unknown_key_is_rejected() throws Exception {
        User admin = newUser(true);

        mockMvc.perform(upload("onecard.nope", mp3(64), "audio/mpeg", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_SOUND_KEY"));
        mockMvc.perform(delete("/api/admin/sounds/onecard.nope").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_SOUND_KEY"));
        // 공개 경로는 모르는 키를 그냥 없다고 한다.
        mockMvc.perform(get("/sounds/onecard.nope")).andExpect(status().isNotFound());
    }

    @Test
    void oversize_empty_and_non_audio_are_rejected() throws Exception {
        User admin = newUser(true);

        mockMvc.perform(upload(KEY, mp3(SoundClipService.MAX_BYTES + 1), "audio/mpeg", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SOUND_TOO_LARGE"));
        mockMvc.perform(upload(KEY, new byte[0], "audio/mpeg", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SOUND"));
        // 선언은 audio/mpeg 이지만 내용이 글자 — 앞머리 바이트로 거른다.
        byte[] text = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(upload(KEY, text, "audio/mpeg", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_SOUND_TYPE"));

        assertThat(clipRepo.count()).isZero();
    }

    @Test
    void upload_then_public_get_with_etag_then_delete() throws Exception {
        User admin = newUser(true);
        byte[] data = mp3(2048);

        // 처음엔 아무것도 없다.
        mockMvc.perform(get("/sounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clips").isEmpty());
        mockMvc.perform(get("/sounds/" + KEY)).andExpect(status().isNotFound());

        // 올린다 — 선언 형식이 달라도(브라우저마다 다르다) 앞머리로 mp3 로 저장한다.
        mockMvc.perform(upload(KEY, data, "application/octet-stream", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.contentType").value("audio/mpeg"))
                .andExpect(jsonPath("$.byteSize").value(2048));

        // 관리 목록 — 허용 키 7개와 올라온 클립 하나.
        mockMvc.perform(get("/api/admin/sounds").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(7))
                .andExpect(jsonPath("$.clips.length()").value(1))
                .andExpect(jsonPath("$.clips[0].key").value(KEY))
                .andExpect(jsonPath("$.maxBytes").value(SoundClipService.MAX_BYTES));

        // 공개 목록(인증 없음) — 키 → 버전.
        mockMvc.perform(get("/sounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clips['" + KEY + "']").isNumber());

        // 공개 재생(인증 없음) — 바이트·형식·ETag·캐시.
        String etag = mockMvc.perform(get("/sounds/" + KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(data))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", "max-age=60, must-revalidate"))
                .andReturn().getResponse().getHeader("ETag");

        // Range 요청에는 206 부분 응답(Safari·iOS <audio> 가 bytes=0-1 로 먼저 묻는다).
        mockMvc.perform(get("/sounds/" + KEY).header("Range", "bytes=0-1"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-1/" + data.length))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(data, 0, 2)));

        // 같은 ETag 면 304, 본문 없음.
        mockMvc.perform(get("/sounds/" + KEY).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));

        // 다시 올리면 덮어쓴다(한 키에 하나).
        mockMvc.perform(upload(KEY, mp3(512), "audio/mpeg", admin)).andExpect(status().isOk());
        assertThat(clipRepo.count()).isEqualTo(1);
        mockMvc.perform(get("/sounds/" + KEY).header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(content().bytes(mp3(512)));

        // 지우면 기본 음성으로 — 공개 경로에서 사라진다. 또 지워도 조용하다.
        mockMvc.perform(delete("/api/admin/sounds/" + KEY).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/sounds/" + KEY).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/sounds/" + KEY)).andExpect(status().isNotFound());
        mockMvc.perform(get("/sounds")).andExpect(jsonPath("$.clips").isEmpty());
    }

    @Test
    void me_reports_admin_flag() throws Exception {
        User admin = newUser(true);
        User user = newUser(false);

        mockMvc.perform(get("/api/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true));
        mockMvc.perform(get("/api/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false));
    }
}
