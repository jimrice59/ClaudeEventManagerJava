package com.eventmanager.client;

import com.eventmanager.client.dto.EventRequest;
import com.eventmanager.client.dto.EventResponse;
import com.eventmanager.client.dto.EventStatus;
import com.eventmanager.client.dto.PagedResponse;
import com.eventmanager.client.dto.PerformerSummary;
import com.eventmanager.client.dto.TicketResponse;
import com.eventmanager.client.dto.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Uses the client's real {@link EventManagerClient#defaultRestTemplate()} (the one the
 * one-argument constructor uses) bound to a MockRestServiceServer, so these tests cover its
 * Jackson configuration as well as request shapes. No real server involved.
 */
class EventManagerClientTest {

    private static final String BASE = "http://localhost:8080";

    /** A ticket as the server actually returns it (shape taken from a live response). */
    private static final String TICKET_JSON = """
            {"id":5,"eventId":4,"eventName":"Encore","eventDate":"2027-01-02T20:00:00","description":null,
             "venueId":1,"venueName":"Levi's Stadium","performers":[{"id":1,"name":"Taylor Swift"}],
             "status":"PAYING","userId":42,"paymentRequestId":"req-1","datePurchased":null}""";

    private MockRestServiceServer server;
    private EventManagerClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = EventManagerClient.defaultRestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new EventManagerClient(BASE, restTemplate);
        client.setToken("test-token");
    }

    @Test
    void getEvents_readsStatusAndIgnoresFieldsTheClientDoesNotKnow() {
        // Regression: a plain ObjectMapper failed the whole call on the server's "status" field.
        server.expect(requestTo(BASE + "/api/v1/events"))
                .andRespond(withSuccess("""
                        [{"id":3,"name":"Test Concert","eventDate":"2026-12-01T19:00:00","ticketPrice":50.00,
                          "ticketsTotal":3,"status":"AVAILABLE","someFutureField":"x"}]""",
                        MediaType.APPLICATION_JSON));

        List<EventResponse> events = client.getEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(EventStatus.AVAILABLE);
        assertThat(events.get(0).getEventDate()).isEqualTo(LocalDateTime.of(2026, 12, 1, 19, 0));
        server.verify();
    }

    @Test
    void unknownEnumValuesReadAsNullInsteadOfFailing() {
        server.expect(requestTo(BASE + "/api/v1/tickets/5"))
                .andRespond(withSuccess(TICKET_JSON.replace("\"PAYING\"", "\"SOME_NEW_STATUS\""),
                        MediaType.APPLICATION_JSON));

        TicketResponse ticket = client.getTicket(5L);

        assertThat(ticket.getStatus()).isNull();
        assertThat(ticket.getId()).isEqualTo(5L);
    }

    @Test
    void getMyTickets_readsAPageOfTicketsWithPerformersAndDates() {
        server.expect(requestTo(BASE + "/api/v1/tickets/me?page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"content\":[" + TICKET_JSON + "],\"page\":0,\"size\":20,"
                        + "\"totalElements\":1,\"totalPages\":1,\"last\":true}", MediaType.APPLICATION_JSON));

        PagedResponse<TicketResponse> page = client.getMyTickets(0, 20);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.isLast()).isTrue();
        TicketResponse ticket = page.getContent().get(0);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.PAYING);
        assertThat(ticket.getEventDate()).isEqualTo(LocalDateTime.of(2027, 1, 2, 20, 0));
        assertThat(ticket.getPerformers()).containsExactly(new PerformerSummary(1L, "Taylor Swift"));
        server.verify();
    }

    @Test
    void getMyTransactions_readsOffsetDatesAndOperationTypes() {
        server.expect(requestTo(BASE + "/api/v1/tickets/me/transactions?page=0&size=20"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"content":[{"id":10,"ticketId":1,"eventId":4,"eventName":"Encore","operation":"PAYMENT_DECLINED",
                          "purchasePrice":100.00,"paymentService":"Square","paymentConfirmationDetails":"Payment declined",
                          "paymentRequestId":"req-1","operationDate":"2026-11-11T22:39:51.469951Z"}],
                         "page":0,"size":20,"totalElements":1,"totalPages":1,"last":true}""", MediaType.APPLICATION_JSON));

        var t = client.getMyTransactions(0, 20).getContent().get(0);

        assertThat(t.getOperation()).isEqualTo(com.eventmanager.client.dto.TicketOperationType.PAYMENT_DECLINED);
        assertThat(t.getOperationDate().toInstant()).isEqualTo(java.time.Instant.parse("2026-11-11T22:39:51.469951Z"));
        server.verify();
    }

    @Test
    void purchaseTicket_postsTokenAndRequestId() {
        server.expect(requestTo(BASE + "/api/v1/tickets/5/purchase"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.paymentToken").value("tok_visa"))
                .andExpect(jsonPath("$.paymentRequestId").value("req-1"))
                .andRespond(withSuccess(TICKET_JSON.replace("\"PAYING\"", "\"SOLD\""), MediaType.APPLICATION_JSON));

        assertThat(client.purchaseTicket(5L, "tok_visa", "req-1").getStatus()).isEqualTo(TicketStatus.SOLD);
        server.verify();
    }

    @Test
    void reserveAvailableTicket_surfacesSoldOutAs409() {
        server.expect(requestTo(BASE + "/api/v1/events/4/tickets/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":409,\"message\":\"No tickets currently available for event 4\"}"));

        assertThatThrownBy(() -> client.reserveAvailableTicket(4L))
                .isInstanceOf(HttpClientErrorException.Conflict.class);
        server.verify();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedUpdateEvent_dropsTicketsTotalAndSendsIsoDates() {
        EventRequest request = new EventRequest();
        request.setName("Renamed");
        request.setEventDate(LocalDateTime.of(2026, 6, 1, 19, 0));
        request.setTicketPrice(new BigDecimal("50.00"));
        request.setTicketsTotal(500);
        request.setVenueId(1L);
        request.setPerformerIds(Set.of(2L));
        server.expect(requestTo(BASE + "/api/v1/events/3"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.eventDate").value("2026-06-01T19:00:00"))
                .andExpect(content().string(not(containsString("ticketsTotal"))))
                .andRespond(withSuccess("{\"id\":3,\"name\":\"Renamed\"}", MediaType.APPLICATION_JSON));

        assertThat(client.updateEvent(3L, request).getName()).isEqualTo("Renamed");
        server.verify();
    }
}
