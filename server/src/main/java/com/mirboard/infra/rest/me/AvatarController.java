package com.mirboard.infra.rest.me;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.UserAvatar;
import com.mirboard.domain.lobby.auth.UserAvatarRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 선택적 코스메틱 아바타(D-80). 업로드/삭제는 본인 인증(`/api/me/avatar`), 조회는
 * 게임 내 상대에게 노출되는 공개 코스메틱이라 비-`/api` 공개 경로(`/avatars/{userId}`)
 * — `<img>` 태그가 Bearer 토큰을 못 싣는 제약도 해소(카드/캐릭터 정적 에셋과 동일 모델).
 */
@RestController
public class AvatarController {

    private final UserAvatarRepository avatars;
    private final AvatarService avatarService;
    private final Clock clock;

    public AvatarController(UserAvatarRepository avatars, AvatarService avatarService, Clock clock) {
        this.avatars = avatars;
        this.avatarService = avatarService;
        this.clock = clock;
    }

    @PostMapping("/api/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@AuthenticationPrincipal AuthPrincipal me,
                       @RequestParam("file") MultipartFile file) {
        if (me == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidAvatarException("파일이 비어 있습니다");
        }
        byte[] png;
        try {
            png = avatarService.normalizeToPng(file.getBytes());
        } catch (IOException e) {
            throw new InvalidAvatarException("파일을 읽을 수 없습니다");
        }
        // user_id 가 assigned PK 라 save() 는 upsert(없으면 insert / 있으면 merge).
        avatars.save(new UserAvatar(me.userId(), png, "image/png", Instant.now(clock)));
    }

    @DeleteMapping("/api/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal me) {
        if (me == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        if (avatars.existsById(me.userId())) {
            avatars.deleteById(me.userId());
        }
    }

    @GetMapping("/avatars/{userId}")
    public ResponseEntity<byte[]> get(@PathVariable long userId) {
        return avatars.findById(userId)
                .map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.getContentType()))
                        .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS))
                        .body(a.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
