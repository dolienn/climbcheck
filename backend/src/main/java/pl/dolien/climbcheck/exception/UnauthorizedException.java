package pl.dolien.climbcheck.exception;

/** Missing or wrong X-Admin-Token on a mutation (adding/removing players). */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
