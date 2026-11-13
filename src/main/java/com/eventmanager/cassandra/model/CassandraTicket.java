package com.eventmanager.cassandra.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Cassandra copy of a currently sold {@link com.eventmanager.model.Ticket}. Inserted when a ticket
 * is sold and deleted when the sale is cancelled (TicketService#writeSoldTicketToCassandra /
 * #removeCancelledTicketFromCassandra, async), and written again for every SOLD ticket when its
 * event is deleted (TicketService#backupAndDeleteAllForEvent, synchronous), where it then stays as
 * the archive. Tickets that aren't sold have no row; a cancelled sale is recorded in
 * ticket_operations.
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
