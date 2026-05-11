package pl.dolien.climbcheck.exception;

public class DashboardNotFoundException extends RuntimeException {
    public DashboardNotFoundException(String message) {
        super(message);
    }
}
