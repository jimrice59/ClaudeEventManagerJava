package com.eventmanager.cassandra.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

@Table("performers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CassandraPerformer {

    @Id
    private Long id;

    @Column("name")
    private String name;

    @Column("genre")
    private String genre;

    @Column("bio")
    private String bio;
}
