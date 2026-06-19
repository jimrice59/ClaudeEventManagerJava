package com.eventmanager.repository;

import com.eventmanager.model.Performer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PerformerRepository extends JpaRepository<Performer, Long> {
    List<Performer> findByGenreIgnoreCase(String genre);
    List<Performer> findByNameContainingIgnoreCase(String name);
}
