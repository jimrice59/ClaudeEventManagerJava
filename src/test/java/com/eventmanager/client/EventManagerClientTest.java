package com.eventmanager.client;

import com.eventmanager.dto.TicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request shapes of EventManagerClient methods, checked against a MockRestServiceServer (no real server). */
class EventManagerClientTest {

    private static final String BASE = "http://localhost:8080";

    private MockRestServiceServer server;
    private EventManagerClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new EventManagerClient(BASE, restTemplate);
        client.setToken("test-token");
    }

    @Test
    void reserveAvailableTicket_postsToTheEventsReserveEndpointWithTheBearerToken() {
        server.expect(requestTo(BASE + "/api/v1/events/7/tickets/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"id\":3,\"eventId\":7,\"status\":\"RESERVED\",\"userId\":42}",
                        MediaType.APPLICATION_JSON));

        TicketResponse ticket = client.reserveAvailableTicket(7L);

        assertThat(ticket.getId()).isEqualTo(3L);
        assertThat(ticket.getEventId()).isEqualTo(7L);
        server.verify();
    }

    @Test
    void reserveAvailableTicket_surfacesSoldOutAs409ClientError() {
        server.expect(requestTo(BASE + "/api/v1/events/7/tickets/reserve"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":409,\"message\":\"No tickets currently available for event 7\"}"));

        assertThatThrownBy(() -> client.reserveAvailableTicket(7L))
                .isInstanceOf(HttpClientErrorException.Conflict.class);
        server.verify();
    }

    @Test
    void getMyTransactions_getsThePagedTransactionsWithTheBearerToken() {
        server.expect(requestTo(BASE + "/api/v1/tickets/me/transactions?page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"content\":[{\"id\":10,\"ticketId\":1,\"eventId\":4,\"eventName\":\"Encore\","
                        + "\"operation\":\"PURCHASE\",\"purchasePrice\":100.00,\"operationDate\":\"2026-11-11T22:39:51Z\"}],"
                        + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}",
                        MediaType.APPLICATION_JSON));

        var page = client.getMyTransactions(0, 20);

        assertThat(page.getContent().get(0).getEventName()).isEqualTo("Encore");
        assertThat(page.getContent().get(0).getOperationDate()).isEqualTo(java.time.OffsetDateTime.parse("2026-11-11T22:39:51Z"));
        server.verify();
    }

    @Test
    void searchEvents_sendsOnlyTheNonNullFilters() {
        server.expect(requestTo(BASE + "/api/v1/events?performerId=2&start=2026-06-01T00:00:00"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.searchEvents(null, 2L, LocalDateTime.of(2026, 6, 1, 0, 0), null)).isEmpty();
        server.verify();
    }
}
