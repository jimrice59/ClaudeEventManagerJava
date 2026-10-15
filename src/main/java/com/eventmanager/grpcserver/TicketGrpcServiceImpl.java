package com.eventmanager.grpcserver;

import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.PerformerSummary;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.grpc.api.GetTicketRequest;
import com.eventmanager.grpc.api.PageQuery;
import com.eventmanager.grpc.api.PerformerSummaryMessage;
import com.eventmanager.grpc.api.PurchaseTicketGrpcRequest;
import com.eventmanager.grpc.api.TicketGrpcServiceGrpc;
import com.eventmanager.grpc.api.TicketIdRequest;
import com.eventmanager.grpc.api.TicketMessage;
import com.eventmanager.grpc.api.TicketPageResponse;
import com.eventmanager.service.TicketService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * gRPC transport for {@link TicketService}'s read/reserve/release/purchase/cancel operations —
 * see event_manager_api.proto and docs/grpc-api-exposure.md. Delegates to the exact same
 * {@code TicketService} calls {@code TicketController}/{@code WebTicketController} use,
 * including the full saga-style {@code purchaseTicket} (see ticket-status-state-machine.md and
 * docs/payment-service-integration.md) — no business logic is duplicated here.
 */
@Component
@RequiredArgsConstructor
public class TicketGrpcServiceImpl extends TicketGrpcServiceGrpc.TicketGrpcServiceImplBase {

    private final TicketService ticketService;

    @Override
    public void getTicket(GetTicketRequest request, StreamObserver<TicketMessage> responseObserver) {
        GrpcCallSupport.run(responseObserver, () -> {
            TicketResponse ticket = ticketService.getTicketById(request.getId());
            responseObserver.onNext(toMessage(ticket));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void getMyTickets(PageQuery request, StreamObserver<TicketPageResponse> responseObserver) {
        GrpcCallSupport.runAuthenticated(responseObserver, () -> {
            PagedResponse<TicketResponse> page = ticketService.getMyTickets(request.getPage(), request.getSize());
            responseObserver.onNext(toPageResponse(page));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void reserveTicket(TicketIdRequest request, StreamObserver<TicketMessage> responseObserver) {
        GrpcCallSupport.runAuthenticated(responseObserver, () -> {
            TicketResponse ticket = ticketService.reserveTicket(request.getId());
            responseObserver.onNext(toMessage(ticket));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void releaseTicket(TicketIdRequest request, StreamObserver<TicketMessage> responseObserver) {
        GrpcCallSupport.runAuthenticated(responseObserver, () -> {
            TicketResponse ticket = ticketService.releaseTicket(request.getId());
            responseObserver.onNext(toMessage(ticket));
            responseObserver.onCompleted();
        });
    }

    /**
     * Blocks the gRPC call-handling thread for up to the payment service's configured deadline
     * (120s by default) — same trade-off the REST endpoint already accepts for this call; see
     * docs/payment-service-integration.md.
     */
    @Override
    public void purchaseTicket(PurchaseTicketGrpcRequest request, StreamObserver<TicketMessage> responseObserver) {
        GrpcCallSupport.runAuthenticated(responseObserver, () -> {
            String paymentRequestId = request.hasPaymentRequestId() ? request.getPaymentRequestId() : null;
            TicketResponse ticket = ticketService.purchaseTicket(request.getId(), request.getPaymentToken(), paymentRequestId);
            responseObserver.onNext(toMessage(ticket));
            responseObserver.onCompleted();
        });
    }

    @Override
    public void cancelTicket(TicketIdRequest request, StreamObserver<TicketMessage> responseObserver) {
        GrpcCallSupport.runAuthenticated(responseObserver, () -> {
            TicketResponse ticket = ticketService.cancelTicket(request.getId());
            responseObserver.onNext(toMessage(ticket));
            responseObserver.onCompleted();
        });
    }

    private TicketMessage toMessage(TicketResponse ticket) {
        TicketMessage.Builder builder = TicketMessage.newBuilder()
                .setId(nullToZero(ticket.getId()))
                .setEventId(nullToZero(ticket.getEventId()))
                .setEventName(nullToEmpty(ticket.getEventName()))
                .setEventDate(ticket.getEventDate() == null ? "" : ticket.getEventDate().toString())
                .setDescription(nullToEmpty(ticket.getDescription()))
                .setVenueId(nullToZero(ticket.getVenueId()))
                .setVenueName(nullToEmpty(ticket.getVenueName()))
                .setStatus(ticket.getStatus() == null ? "" : ticket.getStatus().name());
        if (ticket.getPerformers() != null) {
            builder.addAllPerformers(ticket.getPerformers().stream().map(this::toMessage).toList());
        }
        if (ticket.getUserId() != null) {
            builder.setUserId(ticket.getUserId());
        }
        if (ticket.getPaymentRequestId() != null) {
            builder.setPaymentRequestId(ticket.getPaymentRequestId());
        }
        if (ticket.getDatePurchased() != null) {
            builder.setDatePurchased(ticket.getDatePurchased().toString());
        }
        return builder.build();
    }

    private PerformerSummaryMessage toMessage(PerformerSummary summary) {
        return PerformerSummaryMessage.newBuilder()
                .setId(nullToZero(summary.id()))
                .setName(nullToEmpty(summary.name()))
                .build();
    }

    private TicketPageResponse toPageResponse(PagedResponse<TicketResponse> page) {
        return TicketPageResponse.newBuilder()
                .addAllContent(page.getContent().stream().map(this::toMessage).toList())
                .setPage(page.getPage())
                .setSize(page.getSize())
                .setTotalElements(page.getTotalElements())
                .setTotalPages(page.getTotalPages())
                .setLast(page.isLast())
                .build();
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
