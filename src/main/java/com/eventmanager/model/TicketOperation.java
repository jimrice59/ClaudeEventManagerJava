package com.eventmanager.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An audit log entry recorded whenever a ticket is sold ({@link TicketOperationType#PURCHASE})
 * or cancelled ({@link TicketOperationType#CANCEL}) — see TicketService#recordOperation. Unlike
 * {@link Ticket}, this is a flat log row: {@code eventId}/{@code ticketId}/{@code userId} are
 * plain foreign-key columns rather than JPA associations, since nothing needs to navigate from
 * an operation back to its ticket/event/user — it's written once and never read back through
 * this entity.
 */
@Entity
@Table(name = "ticket_operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @NotNull
    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketOperationType operation;

    @NotNull
    @Column(name = "purchase_price", nullable = false, precision = 7, scale = 2)
    private BigDecimal purchasePrice;

    @NotNull
    @Column(name = "payment_service", nullable = false)
    private String paymentService;

    @NotNull
    @Column(name = "payment_confirmation_details", nullable = false)
    private String paymentConfirmationDetails;

    @NotNull
    @Column(name = "operation_date", nullable = false)
    private LocalDateTime operationDate;
}
