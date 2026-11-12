package com.eventmanager.dto;

import com.eventmanager.model.TicketOperationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One of the caller's ticket transactions (a {@code TicketOperation} row), as returned by
 * {@code GET /api/v1/tickets/me/transactions}. Deliberately omits the row's {@code idempotencyToken}
 * (the payment service's internal token, not meaningful to a customer) and {@code userId} (always
 * the caller).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A purchase, declined payment, or cancellation on one of the caller's tickets")
public class TicketTransactionResponse implements Serializable {

    @Schema(example = "7")
    private Long id;

    @Schema(description = "Ticket the transaction applies to", example = "6")
    private Long ticketId;

    @Schema(description = "The ticket's description (e.g. \"Open Seating\"); null for tickets created before descriptions existed")
    private String ticketDescription;

    @Schema(example = "4")
    private Long eventId;

    @Schema(description = "Name of the event the ticket is for", example = "Taylor Swift - Encore")
    private String eventName;

    @Schema(description = "PURCHASE, PAYMENT_DECLINED, or CANCEL")
    private TicketOperationType operation;

    @Schema(description = "The event's ticket price at the time of the transaction. For PAYMENT_DECLINED "
            + "nothing was charged.", example = "100.00")
    private BigDecimal purchasePrice;

    @Schema(example = "Square")
    private String paymentService;

    @Schema(description = "The payment service's confirmation id for a PURCHASE (SIMULATED-... when "
            + "payments are simulated), or a short note for other operations", example = "SIMULATED-3f2a...")
    private String paymentConfirmationDetails;

    @Schema(description = "Id of the purchase attempt; null for CANCEL")
    private String paymentRequestId;

    @Schema(description = "When it happened, with a UTC offset so clients can show it in the viewer's "
            + "own time zone", example = "2026-11-11T22:39:51.469951Z")
    private OffsetDateTime operationDate;
}
