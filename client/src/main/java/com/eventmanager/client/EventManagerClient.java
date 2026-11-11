package com.eventmanager.client;

import com.eventmanager.client.dto.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * RestTemplate-based client for the Event Manager API.
 *
 * <p>Usage:
 * <pre>{@code
 * EventManagerClient client = new EventManagerClient("http://localhost:8080");
 * client.login("admin", "password");           // stores JWT internally
 * EventResponse event = client.getEvent(1L);   // public — no token needed
 * client.createEvent(request);                 // uses stored JWT
 * }</pre>
 *
 * <p>To inject an OAuth2 client_credentials token instead of a custom JWT:
 * <pre>{@code
 * client.setToken(oauthAccessToken);
 * }</pre>
 *
 * <p>Errors: 4xx responses throw {@code HttpClientErrorException};
 * 5xx responses throw {@code HttpServerErrorException}.
 */
public class EventManagerClient {

    private static final DateTimeFormatter DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private volatile String token;

    public EventManagerClient(String baseUrl) {
        this(baseUrl, defaultRestTemplate());
    }

    public EventManagerClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    public AuthResponse register(RegisterRequest request) {
        return restTemplate.postForObject(baseUrl + "/api/v1/auth/register", request, AuthResponse.class);
    }

    /** Logs in and stores the returned JWT for subsequent authenticated calls. */
    public AuthResponse login(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        AuthResponse response = restTemplate.postForObject(baseUrl + "/api/v1/auth/login", request, AuthResponse.class);
        if (response != null) {
            this.token = response.getToken();
        }
        return response;
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    public List<EventResponse> getEvents() {
        return exchangeList(baseUrl + "/api/v1/events", HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public List<EventResponse> getEventsByVenue(Long venueId) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/events")
                .queryParam("venueId", venueId)
                .toUriString();
        return exchangeList(url, HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public List<EventResponse> getEventsBetween(LocalDateTime start, LocalDateTime end) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/events")
                .queryParam("start", start.format(DT))
                .queryParam("end", end.format(DT))
                .toUriString();
        return exchangeList(url, HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    /**
     * Lists events matching every non-null argument (ANDed); any or all may be null. Date bounds
     * are inclusive and each works on its own. Ordered by event date.
     */
    public List<EventResponse> searchEvents(Long venueId, Long performerId, LocalDateTime start, LocalDateTime end) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/events")
                .queryParamIfPresent("venueId", Optional.ofNullable(venueId))
                .queryParamIfPresent("performerId", Optional.ofNullable(performerId))
                .queryParamIfPresent("start", Optional.ofNullable(start).map(d -> d.format(DT)))
                .queryParamIfPresent("end", Optional.ofNullable(end).map(d -> d.format(DT)))
                .toUriString();
        return exchangeList(url, HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public EventResponse getEvent(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.GET, publicEntity(), EventResponse.class).getBody();
    }

    public EventResponse createEvent(EventRequest request) {
        return restTemplate.exchange(baseUrl + "/api/v1/events", HttpMethod.POST, authEntity(request), EventResponse.class).getBody();
    }

    /** Updates an event. {@code ticketsTotal} isn't part of the request: it's fixed at creation. */
    public EventResponse updateEvent(Long id, UpdateEventRequest request) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.PUT, authEntity(request), EventResponse.class).getBody();
    }

    /**
     * @deprecated use {@link #updateEvent(Long, UpdateEventRequest)}. Kept so existing callers
     * still compile; {@code request.ticketsTotal} is dropped, since the server never changes it.
     */
    @Deprecated
    public EventResponse updateEvent(Long id, EventRequest request) {
        return updateEvent(id, UpdateEventRequest.from(request));
    }

    /** Live count of AVAILABLE tickets for the event, computed by the server from Postgres — not a stored field. */
    public Long getNumAvailableTickets(Long eventId) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + eventId + "/tickets/available/count", HttpMethod.GET, publicEntity(), Long.class).getBody();
    }

    public void deleteEvent(Long id) {
        restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.DELETE, authEntity(null), Void.class);
    }

    // -------------------------------------------------------------------------
    // Venues
    // -------------------------------------------------------------------------

    public List<VenueDto> getVenues() {
        return exchangeList(baseUrl + "/api/v1/venues", HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public List<VenueDto> getVenuesByCity(String city) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/venues")
                .queryParam("city", city)
                .toUriString();
        return exchangeList(url, HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public VenueDto getVenue(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/venues/" + id, HttpMethod.GET, publicEntity(), VenueDto.class).getBody();
    }

    public VenueDto createVenue(VenueDto request) {
        return restTemplate.exchange(baseUrl + "/api/v1/venues", HttpMethod.POST, authEntity(request), VenueDto.class).getBody();
    }

    public VenueDto updateVenue(Long id, VenueDto request) {
        return restTemplate.exchange(baseUrl + "/api/v1/venues/" + id, HttpMethod.PUT, authEntity(request), VenueDto.class).getBody();
    }

    public void deleteVenue(Long id) {
        restTemplate.exchange(baseUrl + "/api/v1/venues/" + id, HttpMethod.DELETE, authEntity(null), Void.class);
    }

    // -------------------------------------------------------------------------
    // Performers
    // -------------------------------------------------------------------------

    public List<PerformerDto> getPerformers() {
        return exchangeList(baseUrl + "/api/v1/performers", HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public List<PerformerDto> searchPerformersByName(String name) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/performers")
                .queryParam("name", name)
                .toUriString();
        return exchangeList(url, HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public List<PerformerDto> getPerformersByGenre(String genre) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/performers")
                .queryParam("genre", genre)
                .toUriString();
        return exchangeList(url, HttpMethod.GET, publicEntity(), new ParameterizedTypeReference<>() {});
    }

    public PerformerDto getPerformer(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/performers/" + id, HttpMethod.GET, publicEntity(), PerformerDto.class).getBody();
    }

    public PerformerDto createPerformer(PerformerDto request) {
        return restTemplate.exchange(baseUrl + "/api/v1/performers", HttpMethod.POST, authEntity(request), PerformerDto.class).getBody();
    }

    public PerformerDto updatePerformer(Long id, PerformerDto request) {
        return restTemplate.exchange(baseUrl + "/api/v1/performers/" + id, HttpMethod.PUT, authEntity(request), PerformerDto.class).getBody();
    }

    public PerformerDto addVideo(Long performerId, String url) {
        VideoRequest request = new VideoRequest();
        request.setUrl(url);
        return restTemplate.exchange(baseUrl + "/api/v1/performers/" + performerId + "/videos", HttpMethod.POST, authEntity(request), PerformerDto.class).getBody();
    }

    public PerformerDto deleteVideo(Long performerId, String url) {
        VideoRequest request = new VideoRequest();
        request.setUrl(url);
        return restTemplate.exchange(baseUrl + "/api/v1/performers/" + performerId + "/videos", HttpMethod.DELETE, authEntity(request), PerformerDto.class).getBody();
    }

    public void deletePerformer(Long id) {
        restTemplate.exchange(baseUrl + "/api/v1/performers/" + id, HttpMethod.DELETE, authEntity(null), Void.class);
    }

    // -------------------------------------------------------------------------
    // Tickets — created and deleted only as a side effect of event create/delete;
    // no external create/delete operation exists.
    // -------------------------------------------------------------------------

    public TicketResponse getTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id, HttpMethod.GET, publicEntity(), TicketResponse.class).getBody();
    }

    /** Paginated AVAILABLE tickets for an event; page is 0-based, size 1–100. */
    public PagedResponse<TicketResponse> getAvailableTickets(Long eventId, int page, int size) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/events/" + eventId + "/tickets/available")
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        return restTemplate.exchange(url, HttpMethod.GET, publicEntity(),
                new ParameterizedTypeReference<PagedResponse<TicketResponse>>() {}).getBody();
    }

    /** Paginated list of every ticket owned by the caller (requires {@link #login} or {@link #setToken} first). */
    public PagedResponse<TicketResponse> getMyTickets(int page, int size) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/tickets/me")
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        return restTemplate.exchange(url, HttpMethod.GET, authEntity(null),
                new ParameterizedTypeReference<PagedResponse<TicketResponse>>() {}).getBody();
    }

    /** Reserves one specific AVAILABLE ticket for the caller. 400 if it isn't AVAILABLE. */
    public TicketResponse reserveTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/reserve", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    /**
     * Reserves the next available ticket for an event, letting the server choose which one.
     * Throws {@code HttpClientErrorException} with 404 if the event doesn't exist, or 409 if no
     * ticket is available.
     */
    public TicketResponse reserveAvailableTicket(Long eventId) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + eventId + "/tickets/reserve", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    /** Releases a ticket the caller has RESERVED, back to AVAILABLE. */
    public TicketResponse releaseTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/release", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    /**
     * Purchases a ticket the caller has RESERVED. Check the returned status: {@code SOLD} means the
     * payment was approved, {@code AVAILABLE} means it was declined and the ticket was released.
     * A 409 means a purchase of this ticket is already in progress; a 502 means the payment service
     * couldn't be reached and the ticket is left {@code PAYING}.
     *
     * @param paymentToken a tokenized payment-method reference from the payment service, never raw card data
     * @param paymentRequestId optional idempotency key; pass {@code null} to let the server generate one
     *                         (returned on {@link TicketResponse#getPaymentRequestId()})
     */
    public TicketResponse purchaseTicket(Long id, String paymentToken, String paymentRequestId) {
        PurchaseTicketRequest request = new PurchaseTicketRequest();
        request.setPaymentToken(paymentToken);
        request.setPaymentRequestId(paymentRequestId);
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/purchase", HttpMethod.POST, authEntity(request), TicketResponse.class).getBody();
    }

    /** Cancels a ticket the caller bought (SOLD), returning it to AVAILABLE. */
    public TicketResponse cancelTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/cancel", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final HttpEntity<Void> PUBLIC_ENTITY;

    static {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        PUBLIC_ENTITY = new HttpEntity<>(headers);
    }

    private HttpEntity<Void> publicEntity() {
        return PUBLIC_ENTITY;
    }

    private <T> HttpEntity<T> authEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String t = this.token;
        if (t != null) {
            headers.setBearerAuth(t);
        }
        return new HttpEntity<>(body, headers);
    }

    private <T> List<T> exchangeList(String url, HttpMethod method, HttpEntity<?> entity,
                                     ParameterizedTypeReference<List<T>> type) {
        return restTemplate.exchange(url, method, entity, type).getBody();
    }

    /**
     * The RestTemplate behind the one-argument constructor. Its mapper is a <em>tolerant reader</em>:
     * fields the server adds later are ignored, and enum values this client doesn't know (say, a
     * new ticket status) read as {@code null}, instead of failing the whole call. A plain
     * {@code new ObjectMapper()} does neither, which is why {@code getEvents()} broke as soon as
     * the server added {@code EventResponse.status}. Dates are written as ISO-8601 strings
     * (e.g. {@code 2026-06-01T19:00:00}), the format the API documents, rather than as arrays.
     * Package-private for tests.
     */
    static RestTemplate defaultRestTemplate() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);
        RestTemplate rt = new RestTemplate();
        rt.getMessageConverters().removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
        rt.getMessageConverters().add(converter);
        return rt;
    }
}
