package com.eventmanager.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(columnDefinition = "TEXT")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TicketStatus status = TicketStatus.AVAILABLE;

    /**
     * Id of the user who reserved/bought this ticket. Null while AVAILABLE. Set from the caller's
     * JWT (never client-supplied) when the status moves to RESERVED; checked against the caller's
     * JWT again on every subsequent transition (release, purchase, cancel) and cleared back to
     * null whenever the ticket returns to AVAILABLE (release, cancel). See TicketService's
     * reserveTicket/releaseTicket/purchaseTicket/cancelTicket.
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * The {@code paymentRequestId} of the purchase attempt that moved this ticket into (or, once
     * resolved, out of) {@code PAYING} — set by {@code TicketService.purchaseTicket} when it
     * commits the AVAILABLE/RESERVED→PAYING transition and never cleared afterward, so the most
     * recent payment attempt (successful or not) stays visible on the ticket itself for
     * reconciliation/support lookups. See ticket-status-state-machine.md.
     */
    @Column(name = "payment_request_id")
    private String paymentRequestId;

    /**
     * Stamped by {@code TicketService.completePayment} when a purchase attempt's payment-service
     * call is APPROVED (RESERVED/PAYING → SOLD); null otherwise (including after a cancellation
     * returns the ticket to AVAILABLE — this is a historical record of the sale, not cleared like
     * {@code userId} is). See ticket-status-state-machine.md.
     */
    @Column(name = "date_purchased")
    private LocalDateTime datePurchased;
}
