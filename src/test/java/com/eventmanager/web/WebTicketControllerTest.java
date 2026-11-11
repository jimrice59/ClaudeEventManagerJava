package com.eventmanager.web;

import com.eventmanager.dto.TicketResponse;
import com.eventmanager.exception.PaymentServiceUnavailableException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Thymeleaf purchase handler's outcome mapping — specifically that a declined
 * payment (which TicketService returns normally, not as an exception) is not reported as a
 * successful purchase.
 */
@ExtendWith(MockitoExtension.class)
class WebTicketControllerTest {

    @Mock
    private TicketService ticketService;

    private WebTicketController controller;
    private RedirectAttributesModelMap redirectAttrs;

    @BeforeEach
    void setUp() {
        controller = new WebTicketController(ticketService);
        redirectAttrs = new RedirectAttributesModelMap();
    }

    private static TicketResponse ticketWithStatus(TicketStatus status) {
        return TicketResponse.builder().id(1L).status(status).build();
    }

    @Test
    void purchase_flashesSuccessWhenPaymentApproved() {
        when(ticketService.purchaseTicket(1L, "tok", null)).thenReturn(ticketWithStatus(TicketStatus.SOLD));

        String view = controller.purchase(1L, "tok", redirectAttrs);

        assertThat(view).isEqualTo("redirect:/ui/tickets/me");
        assertThat(redirectAttrs.getFlashAttributes().get("successMessage")).isEqualTo("Ticket purchased!");
        assertThat(redirectAttrs.getFlashAttributes()).doesNotContainKey("errorMessage");
    }

    @Test
    void purchase_flashesErrorNotSuccessWhenPaymentDeclined() {
        when(ticketService.purchaseTicket(1L, "tok", null)).thenReturn(ticketWithStatus(TicketStatus.AVAILABLE));

        String view = controller.purchase(1L, "tok", redirectAttrs);

        assertThat(view).isEqualTo("redirect:/ui/tickets/me");
        assertThat(redirectAttrs.getFlashAttributes()).doesNotContainKey("successMessage");
        assertThat((String) redirectAttrs.getFlashAttributes().get("errorMessage")).contains("declined");
    }

    @Test
    void purchase_flashesErrorWhenPaymentServiceUnavailable() {
        when(ticketService.purchaseTicket(1L, "tok", null))
                .thenThrow(new PaymentServiceUnavailableException(1L, "req-1"));

        controller.purchase(1L, "tok", redirectAttrs);

        assertThat(redirectAttrs.getFlashAttributes()).doesNotContainKey("successMessage");
        assertThat((String) redirectAttrs.getFlashAttributes().get("errorMessage")).contains("PAYING");
    }

    @Test
    void purchase_flashesServiceMessageWhenPaymentAlreadyInProgress() {
        TicketPaymentInProgressException ex = new TicketPaymentInProgressException(1L);
        when(ticketService.purchaseTicket(1L, "tok", null)).thenThrow(ex);

        controller.purchase(1L, "tok", redirectAttrs);

        assertThat(redirectAttrs.getFlashAttributes().get("errorMessage")).isEqualTo(ex.getMessage());
    }
}
