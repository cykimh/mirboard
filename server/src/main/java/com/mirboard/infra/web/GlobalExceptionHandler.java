package com.mirboard.infra.web;

import com.mirboard.domain.game.core.GameNotFoundException;
import com.mirboard.domain.lobby.auth.InvalidCredentialsException;
import com.mirboard.domain.lobby.auth.InvalidPasswordException;
import com.mirboard.domain.lobby.auth.InvalidUsernameException;
import com.mirboard.domain.lobby.auth.UsernameTakenException;
import com.mirboard.domain.lobby.room.AlreadyInRoomException;
import com.mirboard.domain.lobby.room.GameAlreadyStartedException;
import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.ResyncNotAvailableException;
import com.mirboard.domain.lobby.room.RoomFullException;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(ResyncNotAvailableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleResyncNotAvailable(ResyncNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorEnvelope.of("RESYNC_NOT_AVAILABLE",
                        "No active game state to resync",
                        Map.of("roomId", e.roomId())));
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
