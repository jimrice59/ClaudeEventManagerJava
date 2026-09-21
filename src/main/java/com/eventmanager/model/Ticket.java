package com.eventmanager.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

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
}
