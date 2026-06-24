package com.eventmanager.cassandra.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CassandraEvent {

    @Id
    private Long id;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("event_date")
    private LocalDateTime eventDate;

    @Column("ticket_price")
    private BigDecimal ticketPrice;

    @Column("tickets_available")
    private Integer ticketsAvailable;

    @Column("venue_id")
    private Long venueId;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
