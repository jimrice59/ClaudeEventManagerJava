package com.eventmanager.controller;

import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.TicketListPage;
import com.eventmanager.dto.TicketTransactionResponse;
import com.eventmanager.model.TicketOperationType;
import com.eventmanager.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/tickets/me/transactions: auth (401, not 403) and response shape. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TicketService ticketService;

    @Test
    void myTransactions_withoutTokenIs401NotA403() throws Exception {
        // Regression guard: GET /api/v1/tickets/** is permitAll, so without the "/me/**" matcher an
        // anonymous request would reach @PreAuthorize and get 403 instead of 401.
        mockMvc.perform(get("/api/v1/tickets/me/transactions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ticketService);
    }

    @Test
    @WithMockUser
    void myTransactions_returnsZonedDatesAndNeverTheIdempotencyToken() throws Exception {
        TicketTransactionResponse t = TicketTransactionResponse.builder()
                .id(10L).ticketId(1L).eventId(4L).eventName("Encore").operation(TicketOperationType.PURCHASE)
                .purchasePrice(new BigDecimal("100.00")).paymentService("Square")
                .paymentConfirmationDetails("SIMULATED-abc").paymentRequestId("req-1")
                .operationDate(OffsetDateTime.of(2026, 11, 11, 22, 39, 51, 0, ZoneOffset.UTC)).build();
        when(ticketService.getMyTransactions(anyInt(), anyInt()))
                .thenReturn(TicketListPage.of(PagedResponse.<TicketTransactionResponse>builder()
                        .content(List.of(t)).page(0).size(20).totalElements(1).totalPages(1).last(true).build(),
                        java.util.Map.of(4L, 60003L)));

        mockMvc.perform(get("/api/v1/tickets/me/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventName").value("Encore"))
                .andExpect(jsonPath("$.content[0].operation").value("PURCHASE"))
                .andExpect(jsonPath("$.content[0].operationDate").value("2026-11-11T22:39:51Z"))
                .andExpect(jsonPath("$.maxTicketIdByEvent['4']").value(60003))
                .andExpect(content().string(not(containsString("idempotency"))));
    }
}
