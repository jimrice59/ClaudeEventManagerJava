package com.eventmanager.cassandra.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Archival copy of a {@link com.eventmanager.model.Ticket}, written only when its event
 * is deleted (see TicketService#backupAndDeleteAllForEvent) — tickets are never written
 * to Cassandra at creation time.
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
}
