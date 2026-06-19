package com.eventmanager.repository;

import com.eventmanager.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("SELECT e FROM Event e JOIN FETCH e.venue LEFT JOIN FETCH e.performers WHERE e.id = :id")
    Optional<Event> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT DISTINCT e FROM Event e JOIN FETCH e.venue LEFT JOIN FETCH e.performers")
    List<Event> findAllWithDetails();

    List<Event> findByEventDateBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT DISTINCT e FROM Event e JOIN FETCH e.venue LEFT JOIN FETCH e.performers " +
           "WHERE e.venue.id = :venueId")
    List<Event> findByVenueId(@Param("venueId") Long venueId);
}
