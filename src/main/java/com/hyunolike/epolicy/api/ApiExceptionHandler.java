package com.hyunolike.epolicy.api;

import com.hyunolike.epolicy.application.service.IssuanceFailedException;
import com.hyunolike.epolicy.domain.masking.PersonalDataLeakException;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API 오류 응답. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    /**
     * 마스킹 누락은 절대 200 으로 넘기지 않는다.
     *
     * <p>메시지에 문제의 값을 담지 않는 것도 중요하다. 개인정보가 샜다는 사실을 알리려다 응답 본문으로
     * 한 번 더 새면 본말전도다.
     */
    @ExceptionHandler(PersonalDataLeakException.class)
    public ResponseEntity<ErrorResponse> handleLeak(PersonalDataLeakException e) {
        log.error("마스킹 정책 위반이 감지되었습니다", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("MASKING_VIOLATION", "마스킹 정책 위반으로 발급을 중단했습니다"));
    }

    /**
     * 발급 실패.
     *
     * <p>마스킹 위반은 파이프라인 안에서 {@link IssuanceFailedException} 에 싸여 올라온다. 원인
     * 체인을 풀어 따로 분류하지 않으면 "발급 실패" 로 뭉뚱그려져, 데이터 문제와 개인정보 사고가
     * 같은 코드로 보고된다. 둘은 대응하는 사람도 긴급도도 다르다.
     */
    @ExceptionHandler(IssuanceFailedException.class)
    public ResponseEntity<ErrorResponse> handleIssuanceFailure(IssuanceFailedException e) {
        if (hasCause(e, PersonalDataLeakException.class)) {
            log.error("마스킹 정책 위반으로 발급을 중단했습니다 (stage={})", e.stage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("MASKING_VIOLATION",
                            "마스킹 정책 위반으로 발급을 중단했습니다", e.stage(), Instant.now()));
        }
        log.warn("발급 실패", e);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("ISSUANCE_FAILED", e.getMessage(), e.stage(), Instant.now()));
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable cursor = throwable; cursor != null; cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                return false;
            }
        }
        return false;
    }

    public record ErrorResponse(String code, String message, String stage, Instant timestamp) {

        static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, null, Instant.now());
        }
    }
}
