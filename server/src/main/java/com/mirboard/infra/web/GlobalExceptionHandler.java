package com.mirboard.infra.web;

import com.mirboard.domain.admin.ChatMessageNotFoundException;
import com.mirboard.domain.admin.DuplicateReportException;
import com.mirboard.domain.admin.NotAdminException;
import com.mirboard.domain.admin.SelfReportException;
import com.mirboard.domain.game.core.GameNotFoundException;
import com.mirboard.domain.lobby.auth.AccountLockedException;
import com.mirboard.domain.lobby.auth.AccountSuspendedException;
import com.mirboard.domain.lobby.auth.InvalidCredentialsException;
import com.mirboard.domain.lobby.auth.InvalidPasswordException;
import com.mirboard.domain.lobby.auth.InvalidUsernameException;
import com.mirboard.domain.lobby.auth.UsernameTakenException;
import com.mirboard.domain.lobby.room.AlreadyInRoomException;
import com.mirboard.domain.lobby.room.GameAlreadyStartedException;
import com.mirboard.domain.lobby.room.GameNotInProgressException;
import com.mirboard.domain.lobby.room.InvalidCapacityException;
import com.mirboard.domain.lobby.room.InvalidStakeException;
import com.mirboard.domain.lobby.room.NotHostException;
import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.StakedRoomNoBotsException;
import com.mirboard.domain.lobby.room.ResyncNotAvailableException;
import com.mirboard.domain.lobby.room.RoomFullException;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.infra.rest.me.InvalidAvatarException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidUsernameException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidUsername(InvalidUsernameException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("INVALID_INPUT", "Invalid username",
                        Map.of("field", "username")));
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidPassword(InvalidPasswordException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("INVALID_INPUT", e.getMessage(),
                        Map.of("field", "password")));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ApiErrorEnvelope> handleUsernameTaken(UsernameTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("USERNAME_TAKEN", "Username already taken",
                        Map.of("username", e.username())));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBadCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorEnvelope.of("BAD_CREDENTIALS", "Invalid username or password"));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAccountLocked(AccountLockedException e) {
        // D-84 — 로그인 실패 누적 잠금. 423 Locked.
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ApiErrorEnvelope.of("ACCOUNT_LOCKED",
                        "로그인 시도가 많아 계정이 일시적으로 잠겼습니다. 잠시 후 다시 시도하세요."));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiErrorEnvelope> handleTooManyRequests(TooManyRequestsException e) {
        // D-84 — 인증 IP 레이트리밋 초과. 429 Too Many Requests.
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiErrorEnvelope.of("TOO_MANY_REQUESTS",
                        "요청이 너무 많습니다. 잠시 후 다시 시도하세요."));
    }

    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAccountSuspended(AccountSuspendedException e) {
        // D-86 — 정지된 계정. 403 Forbidden.
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorEnvelope.of("ACCOUNT_SUSPENDED", "정지된 계정입니다. 관리자에게 문의하세요."));
    }

    @ExceptionHandler(ChatMessageNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleChatMessageNotFound(ChatMessageNotFoundException e) {
        // D-93 — 링버퍼(최근 100개·TTL 2h)에 없음. 대개 "너무 오래된 메시지".
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorEnvelope.of("CHAT_MESSAGE_NOT_FOUND",
                        "신고할 메시지를 찾을 수 없습니다. 너무 오래된 메시지일 수 있습니다."));
    }

    @ExceptionHandler(SelfReportException.class)
    public ResponseEntity<ApiErrorEnvelope> handleSelfReport(SelfReportException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorEnvelope.of("SELF_REPORT", "자기 메시지는 신고할 수 없습니다."));
    }

    @ExceptionHandler(DuplicateReportException.class)
    public ResponseEntity<ApiErrorEnvelope> handleDuplicateReport(DuplicateReportException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("DUPLICATE_REPORT", "이미 신고한 메시지입니다."));
    }

    @ExceptionHandler(NotAdminException.class)
    public ResponseEntity<ApiErrorEnvelope> handleNotAdmin(NotAdminException e) {
        // D-86 — 어드민 권한 없음. 403 Forbidden.
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorEnvelope.of("NOT_ADMIN", "관리자 권한이 필요합니다"));
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleGameNotFound(GameNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorEnvelope.of("GAME_NOT_AVAILABLE", "Game not found or not available",
                        Map.of("gameId", String.valueOf(e.gameId()))));
    }

    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleRoomNotFound(RoomNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorEnvelope.of("ROOM_NOT_FOUND", "Room not found",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(RoomFullException.class)
    public ResponseEntity<ApiErrorEnvelope> handleRoomFull(RoomFullException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("ROOM_FULL", "Room capacity exceeded",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(AlreadyInRoomException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAlreadyInRoom(AlreadyInRoomException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("ALREADY_IN_ROOM", "User already in room",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(NotInRoomException.class)
    public ResponseEntity<ApiErrorEnvelope> handleNotInRoom(NotInRoomException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("NOT_IN_ROOM", "User is not in the room",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(GameAlreadyStartedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleGameAlreadyStarted(GameAlreadyStartedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("GAME_ALREADY_STARTED", "Game already started",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(NotHostException.class)
    public ResponseEntity<ApiErrorEnvelope> handleNotHost(NotHostException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorEnvelope.of("NOT_HOST", "Only the host can perform this action",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(GameNotInProgressException.class)
    public ResponseEntity<ApiErrorEnvelope> handleGameNotInProgress(GameNotInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorEnvelope.of("GAME_NOT_IN_PROGRESS",
                        "Game is not in progress",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(ResyncNotAvailableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleResyncNotAvailable(ResyncNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorEnvelope.of("RESYNC_NOT_AVAILABLE",
                        "No active game state to resync",
                        Map.of("roomId", e.roomId())));
    }

    @ExceptionHandler(InvalidAvatarException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidAvatar(InvalidAvatarException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("INVALID_AVATAR", e.getMessage()));
    }

    @ExceptionHandler(InvalidStakeException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidStake(InvalidStakeException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("INVALID_STAKE", "허용되지 않은 판돈입니다",
                        Map.of("stake", e.stake())));
    }

    /** D-99 — 게임이 정한 인원 범위를 벗어난 방 생성 요청. */
    @ExceptionHandler(InvalidCapacityException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidCapacity(InvalidCapacityException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("INVALID_CAPACITY", "허용되지 않은 방 인원입니다",
                        Map.of("capacity", e.capacity(),
                                "minPlayers", e.minPlayers(),
                                "maxPlayers", e.maxPlayers())));
    }

    @ExceptionHandler(StakedRoomNoBotsException.class)
    public ResponseEntity<ApiErrorEnvelope> handleStakedRoomNoBots(StakedRoomNoBotsException e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("STAKED_ROOM_NO_BOTS",
                        "판돈 방은 봇으로 채울 수 없습니다"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorEnvelope> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413) // 413 Content Too Large
                .body(ApiErrorEnvelope.of("AVATAR_TOO_LARGE", "이미지가 너무 큽니다(최대 4MB)"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorEnvelope> handleValidation(MethodArgumentNotValidException e) {
        var field = e.getBindingResult().getFieldError();
        Map<String, Object> details = field == null ? null
                : Map.of("field", field.getField(),
                        "rejected", String.valueOf(field.getRejectedValue()));
        return ResponseEntity.badRequest()
                .body(ApiErrorEnvelope.of("INVALID_INPUT", "Request validation failed", details));
    }
}
