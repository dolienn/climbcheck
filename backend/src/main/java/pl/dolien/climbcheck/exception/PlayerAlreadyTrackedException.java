package pl.dolien.climbcheck.exception;

public class PlayerAlreadyTrackedException extends RuntimeException {
    public PlayerAlreadyTrackedException(String message) {
        super(message);
    }
}
