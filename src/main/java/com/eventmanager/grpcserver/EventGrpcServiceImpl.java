package com.eventmanager.grpcserver;

import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.dto.VenueDto;
import com.eventmanager.grpc.api.AvailableTicketCountResponse;
import com.eventmanager.grpc.api.EventGrpcServiceGrpc;
import com.eventmanager.grpc.api.EventMessage;
import com.eventmanager.grpc.api.GetEventRequest;
import com.eventmanager.grpc.api.ListEventsRequest;
import com.eventmanager.grpc.api.ListEventsResponse;
import com.eventmanager.grpc.api.PerformerMessage;
import com.eventmanager.grpc.api.VenueMessage;
import com.eventmanager.service.EventService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * gRPC transport for a subset of {@link EventService} — see event_manager_api.proto and
 * docs/grpc-api-exposure.md. Delegates to the exact same service calls
 * {@code EventController}/{@code WebEventController} use; no business logic lives here, only
 * request/response translation (the same split {@code TicketController} keeps from
 * {@code TicketService}).
 */
@Component
@RequiredArgsConstructor
public class EventGrpcServiceImpl extends EventGrpcServiceGrpc.EventGrpcServiceImplBase {

    private final EventService eventService;

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
        return VenueMessage.newBuilder()
                .setId(nullToZero(venue.getId()))
                .setName(nullToEmpty(venue.getName()))
                .setAddress(nullToEmpty(venue.getAddress()))
                .setCity(nullToEmpty(venue.getCity()))
                .setState(nullToEmpty(venue.getState()))
                .setZipCode(nullToEmpty(venue.getZipCode()))
                .setCapacity(venue.getCapacity() == null ? 0 : venue.getCapacity())
                .build();
    }

    private PerformerMessage toMessage(PerformerDto performer) {
        return PerformerMessage.newBuilder()
                .setId(nullToZero(performer.getId()))
                .setName(nullToEmpty(performer.getName()))
                .setGenre(nullToEmpty(performer.getGenre()))
                .setBio(nullToEmpty(performer.getBio()))
                .addAllVideoUrls(performer.getVideoUrls() == null ? List.of() : performer.getVideoUrls())
                .build();
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
