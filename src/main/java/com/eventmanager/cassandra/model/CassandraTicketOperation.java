package com.eventmanager.cassandra.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Archival copy of a {@link com.eventmanager.model.TicketOperation}, written asynchronously
 * whenever one is recorded (see CassandraAsyncWriter#saveTicketOperation). Unlike ticket backups
 * (written only on event deletion), these are dual-written at creation time, the same pattern
 * events and performers use — and, also like events, never deleted from Cassandra: when an event
 * is deleted its operations are removed from Postgres only, leaving the Cassandra copies as an
 * archive (see TicketService#backupAndDeleteAllForEvent).
 */
@Table("ticket_operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CassandraTicketOperation {

    @Id
    private Long id;

    @Column("event_id")
    private Long eventId;

    @Column("ticket_id")
    private Long ticketId;

    @Column("user_id")
    private Long userId;

    @Column("operation")
    private String operation;

    @Column("purchase_price")
    private BigDecimal purchasePrice;

    @Column("payment_service")
    private String paymentService;

    @Column("payment_confirmation_details")
    private String paymentConfirmationDetails;

    @Column("operation_date")
    private LocalDateTime operationDate;
}
