package pl.dolien.climbcheck.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DashboardNotFoundException.class)
    public ResponseEntity<ApiError> handleDashboardNotFound(DashboardNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PlayerNotFoundException.class)
    public ResponseEntity<ApiError> handlePlayerNotFound(PlayerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PlayerAlreadyTrackedException.class)
    public ResponseEntity<ApiError> handlePlayerAlreadyTracked(PlayerAlreadyTrackedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ApiError(message));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfter().toSeconds()))
                .body(new ApiError("Too many requests, try again in " + ex.getRetryAfter().toSeconds() + "s"));
    }

    @ExceptionHandler(RiotRateLimitException.class)
    public ResponseEntity<ApiError> handleRiotRateLimit(RiotRateLimitException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        String message = "Riot API rate limit exceeded";
        if (ex.getRetryAfter() != null) {
            response.header("Retry-After", String.valueOf(ex.getRetryAfter().toSeconds()));
            message += ", retry after " + ex.getRetryAfter().toSeconds() + "s";
        }
        return response.body(new ApiError(message));
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleRiotApiError(RestClientResponseException ex) {
        // An invalid/expired Riot API key is an operator problem, not a client one — a
        // distinct message makes it recognizable (dev keys expire every 24h) while the
        // status stays 502. Everything else exposes only the status code, never details.
        if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiError("Riot API key invalid or expired"));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("Riot API error: " + ex.getStatusCode()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("Data conflict: " + ex.getMostSpecificCause().getMessage()));
    }
}
