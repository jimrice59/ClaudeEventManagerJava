package com.eventmanager.grpcserver;

import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.dto.VenueDto;
import com.eventmanager.grpc.api.AvailableTicketCountResponse;
import com.eventmanager.grpc.api.CreateEventRequest;
import com.eventmanager.grpc.api.EventGrpcServiceGrpc;
import com.eventmanager.grpc.api.EventIdRequest;
import com.eventmanager.grpc.api.EventMessage;
import com.eventmanager.grpc.api.GetEventRequest;
import com.eventmanager.grpc.api.ListEventsRequest;
import com.eventmanager.grpc.api.ListEventsResponse;
import com.eventmanager.grpc.api.PerformerMessage;
import com.eventmanager.grpc.api.UpdateEventRequest;
import com.eventmanager.grpc.api.VenueMessage;
import com.eventmanager.service.EventService;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * gRPC transport for {@link EventService} — see event_manager_api.proto and
 * docs/grpc-api-exposure.md. Delegates to the exact same service calls
 * {@code EventController}/{@code WebEventController} use; no business logic lives here, only
 * request/response translation (the same split {@code TicketController} keeps from
 * {@code TicketService}). {@code GetEvent}/{@code ListEvents}/{@code GetAvailableTicketCount} are
 * public ({@link GrpcCallSupport#run}); {@code CreateEvent}/{@code UpdateEvent}/
 * {@code DeleteEvent} require {@code ROLE_ADMIN} ({@link GrpcCallSupport#runAdmin}), matching
 * {@code EventController}'s own {@code @PreAuthorize("hasRole('ADMIN')")}.
 */
@Component
@RequiredArgsConstructor
public class EventGrpcServiceImpl extends EventGrpcServiceGrpc.EventGrpcServiceImplBase {

    private final EventService eventService;
    private final Validator validator;

    @Override
    public void getEvent(GetEventRequest request, StreamObserver<EventMessage> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            EventResponse event = eventService.getEventById(request.getId());
            responseObserver.onNext(toMessage(event));
            responseObserver.onCompleted();
        });
    }

    /** No {@code venue_id} filter → every event (mirrors {@code GET /api/v1/events} with no query param). */
    @Override
    public void listEvents(ListEventsRequest request, StreamObserver<ListEventsResponse> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            List<EventResponse> events = request.hasVenueId()
                    ? eventService.getEventsByVenue(request.getVenueId())
                    : eventService.getAllEvents();
            responseObserver.onNext(ListEventsResponse.newBuilder()
                    .addAllEvents(events.stream().map(this::toMessage).toList())
                    .build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void getAvailableTicketCount(GetEventRequest request, StreamObserver<AvailableTicketCountResponse> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            long count = eventService.getNumAvailableTickets(request.getId());
            responseObserver.onNext(AvailableTicketCountResponse.newBuilder().setCount(count).build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void createEvent(CreateEventRequest request, StreamObserver<EventMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            EventRequest dto = new EventRequest();
            dto.setName(request.getName());
            dto.setDescription(request.getDescription());
            dto.setEventDate(LocalDateTime.parse(request.getEventDate()));
            dto.setTicketPrice(new BigDecimal(request.getTicketPrice()));
            dto.setTicketsTotal(request.getTicketsTotal());
            dto.setVenueId(request.getVenueId());
            dto.setPerformerIds(toPerformerIds(request.getPerformerIdsList()));
            GrpcValidationSupport.validate(validator, dto);
            EventResponse saved = eventService.createEvent(dto);
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    /**
     * No {@code tickets_total} field on {@code UpdateEventRequest} (see event_manager_api.proto)
     * — {@code EventService.updateEvent} ignores it even when present on the REST
     * {@code EventRequest} body (it's immutable after creation), so this new surface doesn't
     * offer a field that would be silently dropped. The mapped {@code EventRequest} still needs
     * *some* value in that {@code @NotNull} field to pass validation; {@code 0} is a placeholder
     * that is never read by {@code updateEvent}.
     */
    @Override
    public void updateEvent(UpdateEventRequest request, StreamObserver<EventMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            EventRequest dto = new EventRequest();
            dto.setName(request.getName());
            dto.setDescription(request.getDescription());
            dto.setEventDate(LocalDateTime.parse(request.getEventDate()));
            dto.setTicketPrice(new BigDecimal(request.getTicketPrice()));
            dto.setTicketsTotal(0);
            dto.setVenueId(request.getVenueId());
            dto.setPerformerIds(toPerformerIds(request.getPerformerIdsList()));
            GrpcValidationSupport.validate(validator, dto);
            EventResponse saved = eventService.updateEvent(request.getId(), dto);
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void deleteEvent(EventIdRequest request, StreamObserver<Empty> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            eventService.deleteEvent(request.getId());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    private Set<Long> toPerformerIds(List<Long> performerIds) {
        return new HashSet<>(performerIds);
    }

    private EventMessage toMessage(EventResponse event) {
        EventMessage.Builder builder = EventMessage.newBuilder()
                .setId(nullToZero(event.getId()))
                .setName(nullToEmpty(event.getName()))
                .setDescription(nullToEmpty(event.getDescription()))
                .setEventDate(event.getEventDate() == null ? "" : event.getEventDate().toString())
                .setTicketPrice(event.getTicketPrice() == null ? "" : event.getTicketPrice().toPlainString())
                .setTicketsTotal(event.getTicketsTotal() == null ? 0 : event.getTicketsTotal())
                .setStatus(event.getStatus() == null ? "" : event.getStatus().name())
                .setCreatedAt(event.getCreatedAt() == null ? "" : event.getCreatedAt().toString())
                .setUpdatedAt(event.getUpdatedAt() == null ? "" : event.getUpdatedAt().toString());
        if (event.getVenue() != null) {
            builder.setVenue(toMessage(event.getVenue()));
        }
        if (event.getPerformers() != null) {
            builder.addAllPerformers(event.getPerformers().stream().map(this::toMessage).toList());
        }
        return builder.build();
    }

    private VenueMessage toMessage(VenueDto venue) {
        VenueMessage.Builder builder = VenueMessage.newBuilder()
                .setId(nullToZero(venue.getId()))
                .setName(nullToEmpty(venue.getName()))
                .setAddress(nullToEmpty(venue.getAddress()))
                .setCity(nullToEmpty(venue.getCity()))
                .setState(nullToEmpty(venue.getState()))
                .setCapacity(venue.getCapacity() == null ? 0 : venue.getCapacity());
        if (venue.getZipCode() != null) {
            builder.setZipCode(venue.getZipCode());
        }
        return builder.build();
    }

    private PerformerMessage toMessage(PerformerDto performer) {
        PerformerMessage.Builder builder = PerformerMessage.newBuilder()
                .setId(nullToZero(performer.getId()))
                .setName(nullToEmpty(performer.getName()))
                .addAllVideoUrls(performer.getVideoUrls() == null ? List.of() : performer.getVideoUrls());
        if (performer.getGenre() != null) {
            builder.setGenre(performer.getGenre());
        }
        if (performer.getBio() != null) {
            builder.setBio(performer.getBio());
        }
        return builder.build();
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
