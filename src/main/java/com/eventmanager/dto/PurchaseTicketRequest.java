package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request to purchase a reserved ticket, charged via the payment service")
public class PurchaseTicketRequest {

    /**
     * A tokenized reference to a previously-vaulted payment method (e.g. a Stripe/Square
     * payment-method token) — never raw card data. Passed through unchanged to the payment
     * service's {@code PurchaseTicket} call as {@code payment_token}.
     */
    @NotBlank
    @Schema(example = "pm_tok_9f8c2a3b", description = "Tokenized payment method reference from the payment service — never raw card data")
    private String paymentToken;

    /**
     * Optional caller-supplied idempotency key for this purchase attempt. If omitted,
     * TicketService generates a UUID. Returned to the caller unchanged so it can later be used to
     * query the payment service for status (e.g. after a 502 from a timed-out call) — see
     * docs/payment-service-integration.md.
     */
    @Schema(example = "7b4d6e2a-9c1f-4a3b-8e5d-1f2a3b4c5d6e",
            description = "Optional client-generated unique id for this purchase attempt, usable later to query payment status")
    private String paymentRequestId;
}
