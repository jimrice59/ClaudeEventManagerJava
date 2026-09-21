package com.eventmanager.repository;

import com.eventmanager.model.TicketOperation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketOperationRepository extends JpaRepository<TicketOperation, Long> {

    /** Removes every operation logged against an event's tickets — called when the event is deleted. */
    void deleteByEventId(Long eventId);
}
