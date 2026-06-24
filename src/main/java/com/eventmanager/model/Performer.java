package com.eventmanager.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "performers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Performer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(length = 100)
    private String genre;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "performer_videos",
            joinColumns = @JoinColumn(name = "performer_id"),
            inverseJoinColumns = @JoinColumn(name = "video_id")
    )
    @Builder.Default
    private Set<Video> videos = new HashSet<>();
}
