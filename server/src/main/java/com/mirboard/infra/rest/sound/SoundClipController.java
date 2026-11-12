package com.mirboard.infra.rest.sound;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.mirboard.domain.admin.AdminAuthorization;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.sound.InvalidSoundClipException;
import com.mirboard.domain.sound.SoundClip;
import com.mirboard.domain.sound.SoundClipKey;
import com.mirboard.domain.sound.SoundClipRepository;
import com.mirboard.domain.sound.SoundClipService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * D-265 — 게임 음성 클립. 올리기·지우기·관리 목록은 어드민({@code /api/admin/sounds}), 재생은 공개 비-{@code /api} 경로
 * ({@code /sounds}, {@code /sounds/{key}}) — {@code <audio>} 요청은 Bearer 를 못 싣는다(아바타 {@code /avatars/{userId}} 와 같은 이유).
 * 검증은 {@link SoundClipService} 가 한다(규칙#4 — 컨트롤러에 룰 로직 없음).
 */
@RestController
public class SoundClipController {

    private final AdminAuthorization adminAuth;
    private final SoundClipService sounds;

    public SoundClipController(AdminAuthorization adminAuth, SoundClipService sounds) {
        this.adminAuth = adminAuth;
        this.sounds = sounds;
    }

    /** 관리 목록 — 허용 키 전부와 올라와 있는 클립. */
    @GetMapping("/api/admin/sounds")
    public AdminSoundsResponse adminList(@AuthenticationPrincipal AuthPrincipal principal) {
        adminAuth.requireAdmin(requireUserId(principal));
        return new AdminSoundsResponse(
                SoundClipKey.ALLOWED.stream().sorted().toList(),
                sounds.list().stream().map(SoundClipController::view).toList(),
                SoundClipService.MAX_BYTES);
    }

    /** 올리기 — multipart {@code file}. 같은 키는 덮어쓴다. */
    @PutMapping("/api/admin/sounds/{key}")
    public SoundClipView upload(@AuthenticationPrincipal AuthPrincipal principal,
                                @PathVariable String key,
                                @RequestParam("file") MultipartFile file) {
        adminAuth.requireAdmin(requireUserId(principal));
        byte[] data;
        try {
            data = file == null ? null : file.getBytes();
        } catch (IOException e) {
            throw new InvalidSoundClipException("INVALID_SOUND", "파일을 읽을 수 없습니다");
        }
        SoundClip saved = sounds.store(key, data);
        return new SoundClipView(saved.getClipKey(), saved.getContentType(), saved.getByteSize(),
                saved.getUpdatedAt().toEpochMilli());
    }

    @DeleteMapping("/api/admin/sounds/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String key) {
        adminAuth.requireAdmin(requireUserId(principal));
        sounds.delete(key);
    }

    /** 공개 — 올라와 있는 키와 버전(바뀐 시각 ms). 클라가 버전을 주소에 붙여 캐시를 넘긴다. */
    @GetMapping("/sounds")
    public ResponseEntity<PublicSoundsResponse> publicList() {
        Map<String, Long> clips = new LinkedHashMap<>();
        for (SoundClipRepository.Summary s : sounds.list()) {
            clips.put(s.getClipKey(), s.getUpdatedAt().toEpochMilli());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(new PublicSoundsResponse(clips));
    }

    /** 공개 — 클립 파일. ETag 가 같으면 304. */
    @GetMapping("/sounds/{key}")
    public ResponseEntity<byte[]> publicGet(@PathVariable String key, WebRequest request) {
        SoundClip clip = sounds.find(key).orElse(null);
        if (clip == null) {
            return ResponseEntity.notFound().build();
        }
        String etag = "\"" + clip.getUpdatedAt().toEpochMilli() + "-" + clip.getByteSize() + "\"";
        CacheControl cache = CacheControl.maxAge(60, TimeUnit.SECONDS).mustRevalidate();
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(clip.getContentType()))
                .eTag(etag)
                .cacheControl(cache)
                .body(clip.getData());
    }

    private static SoundClipView view(SoundClipRepository.Summary s) {
        return new SoundClipView(s.getClipKey(), s.getContentType(), s.getByteSize(),
                s.getUpdatedAt().toEpochMilli());
    }

    private static long requireUserId(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        return principal.userId();
    }

    public record SoundClipView(String key, String contentType, int byteSize, long updatedAt) {
    }

    public record AdminSoundsResponse(List<String> keys, List<SoundClipView> clips, int maxBytes) {
    }

    public record PublicSoundsResponse(Map<String, Long> clips) {
    }
}
