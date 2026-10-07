package jissuo.chat.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외를 ApiResponse로 바꾸는 유일한 곳 (ADR-018, ADR-019, ADR-020).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ChatException.class)
    ResponseEntity<ApiResponse<Void>> handleChat(ChatException e) {
        return respond(e.errorCode());
    }

    // ADR-019: DB를 고르기 전까지 중복 키 위반은 모두 "이미 멤버"로 본다
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.debug("중복 키 위반", e);
        return respond(ErrorCode.ALREADY_MEMBER);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> handleUnreadable(Exception e) {
        log.debug("잘못된 요청: {}", e.getMessage());
        return respond(ErrorCode.INVALID_REQUEST);
    }

    // Spring MVC가 던지는 요청 오류(검증 실패, 파라미터 누락, 없는 주소, 메서드 불일치 등)는 ErrorResponse로 4xx 상태를 알려 준다.
    // 그 밖의 DataIntegrityViolationException도 여기로 와서 500이 된다 (ADR-019)
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleOthers(Exception e) {
        int status = e instanceof ErrorResponse er ? er.getStatusCode().value() : 500;
        if (status == HttpStatus.NOT_FOUND.value()) {
            return respond(ErrorCode.NOT_FOUND);
        }
        if (status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return respond(ErrorCode.METHOD_NOT_ALLOWED);
        }
        if (status < 500) {
            log.debug("잘못된 요청: {}", e.getMessage());
            return respond(ErrorCode.INVALID_REQUEST);
        }
        log.error("처리하지 못한 예외", e);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code));
    }
}
