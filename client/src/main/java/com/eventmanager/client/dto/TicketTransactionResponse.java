package com.eventmanager.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One of the caller's ticket transactions. The server never sends the payment idempotency token. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketTransactionResponse {
    private Long id;
    private Long ticketId;
    private Long eventId;
    private String eventName;
    private TicketOperationType operation;
    /** The event's ticket price at the time; nothing was charged for PAYMENT_DECLINED. */
    private BigDecimal purchasePrice;
    private String paymentService;
    private String paymentConfirmationDetails;
    private String paymentRequestId;
    /** When it happened, with a UTC offset. */
    private OffsetDateTime operationDate;
}
