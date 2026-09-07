package com.chatserver.common;

import com.chatserver.chatroom.exception.ChatRoomClosedException;
import com.chatserver.chatroom.exception.ChatRoomNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.reduce((a, b) -> a + "; " + b)
				.orElse("Invalid request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_FAILED", details, null));
	}

	@ExceptionHandler(ChatRoomNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(ChatRoomNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}

	@ExceptionHandler(ChatRoomClosedException.class)
	public ResponseEntity<ErrorResponse> handleClosed(ChatRoomClosedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}
}
