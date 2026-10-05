package app.sprout.goals.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the goals contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in first"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "No Sprout account"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No such pot"),
    UNKNOWN_INSTRUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown instrument"),
    TOO_MANY_POTS(HttpStatus.CONFLICT, "Too many open pots"),
    POT_STATE(HttpStatus.CONFLICT, "Not in that state"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough money"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
