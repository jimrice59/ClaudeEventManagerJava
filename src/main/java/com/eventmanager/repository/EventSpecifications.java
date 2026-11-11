package com.eventmanager.repository;

import com.eventmanager.model.Event;
import com.eventmanager.service.EventSearchCriteria;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** JPA Specifications behind EventService#searchEvents — one predicate per non-null filter. */
public final class EventSpecifications {

    private EventSpecifications() {
    }

    public static Specification<Event> matching(EventSearchCriteria criteria) {
        return (root, query, cb) -> {
            // Fetch venue + performers in the same query (no N+1 while mapping to EventResponse).
            // Skipped for a count query, which can't carry fetch joins.
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("venue", JoinType.INNER);
                root.fetch("performers", JoinType.LEFT);
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();
            if (criteria.venueId() != null) {
                predicates.add(cb.equal(root.get("venue").get("id"), criteria.venueId()));
            }
            if (criteria.performerId() != null) {
                // A subquery, NOT a predicate on the fetched "performers" join above: filtering
                // the fetch join would also truncate each returned event's performers collection
                // to just the matched performer.
                Subquery<Long> withPerformer = query.subquery(Long.class);
                Root<Event> e2 = withPerformer.from(Event.class);
                Join<Object, Object> p = e2.join("performers");
                withPerformer.select(e2.get("id")).where(cb.equal(p.get("id"), criteria.performerId()));
                predicates.add(root.get("id").in(withPerformer));
            }
            if (criteria.start() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventDate"), criteria.start()));
            }
            if (criteria.end() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventDate"), criteria.end()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
