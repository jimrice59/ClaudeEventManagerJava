package com.eventmanager.cassandra.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Cassandra copy of a {@link com.eventmanager.model.Ticket}. Written (upserted by id) whenever a
 * ticket is sold or cancelled (TicketService#mirrorTicketToCassandra, async), and again for every
 * SOLD ticket when its event is deleted (TicketService#backupAndDeleteAllForEvent, synchronous).
 * Tickets are never written at creation, so a ticket that was never sold has no row; a cancelled
 * one keeps a row showing AVAILABLE with no user_id (its buyer is in ticket_operations).
 */
@Table("tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CassandraTicket {

    @Id
    private Long id;

    @Column("event_id")
    private Long eventId;

    @Column("description")
    private String description;

    @Column("status")
    private String status;

    @Column("user_id")
    private Long userId;

    @Column("date_purchased")
    private LocalDateTime datePurchased;
}
