package com.eventmanager.grpcserver;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.grpc.api.CreateVenueRequest;
import com.eventmanager.grpc.api.ListVenuesRequest;
import com.eventmanager.grpc.api.ListVenuesResponse;
import com.eventmanager.grpc.api.UpdateVenueRequest;
import com.eventmanager.grpc.api.VenueGrpcServiceGrpc;
import com.eventmanager.grpc.api.VenueIdRequest;
import com.eventmanager.grpc.api.VenueMessage;
import com.eventmanager.service.VenueService;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * gRPC transport for {@link VenueService} — see venue_api.proto and docs/grpc-api-exposure.md.
 * {@code GetVenue}/{@code ListVenues} are public ({@link GrpcCallSupport#run}); the rest require
 * {@code ROLE_ADMIN} ({@link GrpcCallSupport#runAdmin}), matching {@code VenueController}'s own
 * {@code @PreAuthorize("hasRole('ADMIN')")}. Create/update requests are run through
 * {@link GrpcValidationSupport} first, replicating what {@code @Valid} does for free on the REST
 * path.
 */
@Component
@RequiredArgsConstructor
public class VenueGrpcServiceImpl extends VenueGrpcServiceGrpc.VenueGrpcServiceImplBase {

    private final VenueService venueService;
    private final Validator validator;

    @Override
    public void getVenue(VenueIdRequest request, StreamObserver<VenueMessage> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            VenueDto venue = venueService.getVenueById(request.getId());
            responseObserver.onNext(toMessage(venue));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void listVenues(ListVenuesRequest request, StreamObserver<ListVenuesResponse> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            var venues = request.hasCity() ? venueService.getVenuesByCity(request.getCity()) : venueService.getAllVenues();
            responseObserver.onNext(ListVenuesResponse.newBuilder()
                    .addAllVenues(venues.stream().map(this::toMessage).toList())
                    .build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void createVenue(CreateVenueRequest request, StreamObserver<VenueMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            VenueDto dto = VenueDto.builder()
                    .name(request.getName())
                    .address(request.getAddress())
                    .city(request.getCity())
                    .state(request.getState())
                    .zipCode(request.hasZipCode() ? request.getZipCode() : null)
                    .capacity(request.getCapacity())
                    .build();
            GrpcValidationSupport.validate(validator, dto);
            VenueDto saved = venueService.createVenue(dto);
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void updateVenue(UpdateVenueRequest request, StreamObserver<VenueMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            VenueDto dto = VenueDto.builder()
                    .name(request.getName())
                    .address(request.getAddress())
                    .city(request.getCity())
                    .state(request.getState())
                    .zipCode(request.hasZipCode() ? request.getZipCode() : null)
                    .capacity(request.getCapacity())
                    .build();
            GrpcValidationSupport.validate(validator, dto);
            VenueDto saved = venueService.updateVenue(request.getId(), dto);
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void deleteVenue(VenueIdRequest request, StreamObserver<Empty> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            venueService.deleteVenue(request.getId());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    private VenueMessage toMessage(VenueDto venue) {
        VenueMessage.Builder builder = VenueMessage.newBuilder()
                .setId(venue.getId() == null ? 0L : venue.getId())
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
