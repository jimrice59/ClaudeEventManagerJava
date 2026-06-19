package com.eventmanager.repository;

import com.eventmanager.model.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    List<Venue> findByCityIgnoreCase(String city);
    List<Venue> findByCapacityGreaterThanEqual(Integer minCapacity);
}
