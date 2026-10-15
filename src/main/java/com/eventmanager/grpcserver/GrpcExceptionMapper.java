package com.eventmanager.grpcserver;

import com.eventmanager.exception.PaymentServiceUnavailableException;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;

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
