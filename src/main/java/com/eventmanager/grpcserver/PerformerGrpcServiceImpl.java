package com.eventmanager.grpcserver;

import com.eventmanager.dto.PerformerDto;
import com.eventmanager.grpc.api.CreatePerformerRequest;
import com.eventmanager.grpc.api.ListPerformersRequest;
import com.eventmanager.grpc.api.ListPerformersResponse;
import com.eventmanager.grpc.api.PerformerGrpcServiceGrpc;
import com.eventmanager.grpc.api.PerformerIdRequest;
import com.eventmanager.grpc.api.PerformerMessage;
import com.eventmanager.grpc.api.UpdatePerformerRequest;
import com.eventmanager.grpc.api.VideoGrpcRequest;
import com.eventmanager.service.PerformerService;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * gRPC transport for {@link PerformerService} — see performer_api.proto and
 * docs/grpc-api-exposure.md. {@code GetPerformer}/{@code ListPerformers} are public
 * ({@link GrpcCallSupport#run}); everything else requires {@code ROLE_ADMIN}
 * ({@link GrpcCallSupport#runAdmin}), matching {@code PerformerController}'s own
 * {@code @PreAuthorize("hasRole('ADMIN')")} — including {@code AddVideo}/{@code RemoveVideo},
 * which are just as ADMIN-gated on the REST API.
 */
@Component
@RequiredArgsConstructor
public class PerformerGrpcServiceImpl extends PerformerGrpcServiceGrpc.PerformerGrpcServiceImplBase {

    private final PerformerService performerService;
    private final Validator validator;

    @Override
    public void getPerformer(PerformerIdRequest request, StreamObserver<PerformerMessage> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            PerformerDto performer = performerService.getPerformerById(request.getId());
            responseObserver.onNext(toMessage(performer));
            responseObserver.onCompleted();
        });
    }

    /** Mirrors GET /api/v1/performers?name=&genre= — name wins if both are set; see performer_api.proto. */
    @Override
    public void listPerformers(ListPerformersRequest request, StreamObserver<ListPerformersResponse> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            List<PerformerDto> performers;
            if (request.hasName()) {
                performers = performerService.searchPerformers(request.getName());
            } else if (request.hasGenre()) {
                performers = performerService.getPerformersByGenre(request.getGenre());
            } else {
                performers = performerService.getAllPerformers();
            }
            responseObserver.onNext(ListPerformersResponse.newBuilder()
                    .addAllPerformers(performers.stream().map(this::toMessage).toList())
                    .build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void createPerformer(CreatePerformerRequest request, StreamObserver<PerformerMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            PerformerDto dto = PerformerDto.builder()
                    .name(request.getName())
                    .genre(request.hasGenre() ? request.getGenre() : null)
                    .bio(request.hasBio() ? request.getBio() : null)
                    .build();
            GrpcValidationSupport.validate(validator, dto);
            PerformerDto saved = performerService.createPerformer(dto);
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void updatePerformer(UpdatePerformerRequest request, StreamObserver<PerformerMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            PerformerDto dto = PerformerDto.builder()
                    .name(request.getName())
                    .genre(request.hasGenre() ? request.getGenre() : null)
                    .bio(request.hasBio() ? request.getBio() : null)
                    .build();
            GrpcValidationSupport.validate(validator, dto);
            PerformerDto saved = performerService.updatePerformer(request.getId(), dto);
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void addVideo(VideoGrpcRequest request, StreamObserver<PerformerMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            PerformerDto saved = performerService.addVideo(request.getPerformerId(), request.getUrl());
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void removeVideo(VideoGrpcRequest request, StreamObserver<PerformerMessage> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            PerformerDto saved = performerService.deleteVideo(request.getPerformerId(), request.getUrl());
            responseObserver.onNext(toMessage(saved));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void deletePerformer(PerformerIdRequest request, StreamObserver<Empty> responseObserver) {
        GrpcCallSupport.runAdmin(responseObserver, () -> {
            performerService.deletePerformer(request.getId());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        });
    }

    private PerformerMessage toMessage(PerformerDto performer) {
        PerformerMessage.Builder builder = PerformerMessage.newBuilder()
                .setId(performer.getId() == null ? 0L : performer.getId())
                .setName(performer.getName() == null ? "" : performer.getName())
                .addAllVideoUrls(performer.getVideoUrls() == null ? List.of() : performer.getVideoUrls());
        if (performer.getGenre() != null) {
            builder.setGenre(performer.getGenre());
        }
        if (performer.getBio() != null) {
            builder.setBio(performer.getBio());
        }
        return builder.build();
    }
}
