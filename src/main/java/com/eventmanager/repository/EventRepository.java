package com.eventmanager.repository;

import com.eventmanager.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    @Query("SELECT e FROM Event e JOIN FETCH e.venue LEFT JOIN FETCH e.performers WHERE e.id = :id")
    Optional<Event> findByIdWithDetails(@Param("id") Long id);

    // Same order as EventService#searchEvents, so every event list is date-ordered.
    @Query("SELECT DISTINCT e FROM Event e JOIN FETCH e.venue LEFT JOIN FETCH e.performers ORDER BY e.eventDate, e.id")
    List<Event> findAllWithDetails();


}
