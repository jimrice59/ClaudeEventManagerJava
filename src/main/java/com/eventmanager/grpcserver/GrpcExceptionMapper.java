package com.eventmanager.grpcserver;

import com.eventmanager.exception.PaymentServiceUnavailableException;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;

import java.util.stream.Collectors;

/**
 * Maps this application's existing REST-layer exceptions to gRPC {@link Status} codes — the
 * gRPC-transport equivalent of {@code GlobalExceptionHandler}'s {@code @ExceptionHandler} methods.
 * Nothing here is REST-specific; the same business exceptions {@code EventService}/
 * {@code TicketService} already throw are reused as-is, only the response shape differs.
 */
@Slf4j
final class GrpcExceptionMapper {

    private GrpcExceptionMapper() {
    }

    static StatusRuntimeException toStatusRuntimeException(Exception e) {
        Status status = switch (e) {
            case GrpcUnauthenticatedException ex -> Status.UNAUTHENTICATED.withDescription(ex.getMessage());
            case ResourceNotFoundException ex -> Status.NOT_FOUND.withDescription(ex.getMessage());
            // Bean Validation run explicitly against a mapped Create/Update DTO — see
            // GrpcValidationSupport. The gRPC equivalent of @Valid's 400 on the REST API; proto3
            // has no built-in field validation, so this is done by hand instead of for free.
            case ConstraintViolationException ex -> Status.INVALID_ARGUMENT.withDescription(
                    ex.getConstraintViolations().stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .collect(Collectors.joining("; ")));
            // event_date/ticket_price are plain strings on the wire (see event_manager_api.proto)
            // precisely because proto3 has no native date/decimal type — a malformed one is a
            // client input error, not a server bug, so INVALID_ARGUMENT rather than INTERNAL.
            case java.time.format.DateTimeParseException ex ->
                    Status.INVALID_ARGUMENT.withDescription("Invalid event_date: " + ex.getMessage());
            case NumberFormatException ex ->
                    Status.INVALID_ARGUMENT.withDescription("Invalid ticket_price: " + ex.getMessage());
            case AccessDeniedException ex -> Status.PERMISSION_DENIED.withDescription("Access denied");
            // 409-equivalent: a conflict with a purchase attempt already in flight for this ticket.
            case TicketPaymentInProgressException ex -> Status.FAILED_PRECONDITION.withDescription(ex.getMessage());
            // 502-equivalent: the payment-service call itself failed; the ticket is left PAYING.
            case PaymentServiceUnavailableException ex -> Status.UNAVAILABLE.withDescription(ex.getMessage());
            // 400-equivalent: every other ticket-status-machine validation failure (wrong status
            // for the requested transition) throws this — see ticket-status-state-machine.md.
            case IllegalArgumentException ex -> Status.FAILED_PRECONDITION.withDescription(ex.getMessage());
            default -> {
                log.error("Unhandled exception in gRPC API call", e);
                yield Status.INTERNAL.withDescription("An unexpected error occurred");
            }
        };
        return status.asRuntimeException();
    }
}
