package com.eventmanager.client.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PurchaseTicketRequest {

    /** A tokenized payment-method reference from the payment service, never raw card data. */
    @NotBlank
    private String paymentToken;

    /** Optional idempotency key for the purchase attempt; the server generates one if null. */
    private String paymentRequestId;
}
