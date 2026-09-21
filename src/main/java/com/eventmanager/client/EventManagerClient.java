package com.eventmanager.client;

import com.eventmanager.dto.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * RestTemplate-based client for the Event Manager API.
 *
 * Usage:
 *   EventManagerClient client = new EventManagerClient("http://localhost:8080");
 *   client.login("admin", "password");          // stores JWT internally
 *   EventResponse event = client.getEvent(1L);  // public — no token needed
 *   client.createEvent(request);                // uses stored JWT
 *
 * Alternatively, inject a token directly:
 *   client.setToken(myJwtString);
 *
 * Errors: 4xx/5xx responses throw HttpClientErrorException / HttpServerErrorException.
 */
public class EventManagerClient {

    private static final DateTimeFormatter DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private String token;

    public EventManagerClient(String baseUrl) {
        this(baseUrl, new RestTemplate());
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

    public EventResponse getEvent(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.GET, publicEntity(), EventResponse.class).getBody();
    }

    public EventResponse createEvent(EventRequest request) {
        return restTemplate.exchange(baseUrl + "/api/v1/events", HttpMethod.POST, authEntity(request), EventResponse.class).getBody();
    }

    public EventResponse updateEvent(Long id, EventRequest request) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.PUT, authEntity(request), EventResponse.class).getBody();
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

    /** Paginated AVAILABLE tickets for an event; page is 0-based. */
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

    public TicketResponse reserveTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/reserve", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    public TicketResponse releaseTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/release", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    public TicketResponse purchaseTicket(Long id, String userCredentials) {
        PurchaseTicketRequest request = new PurchaseTicketRequest();
        request.setUserCredentials(userCredentials);
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/purchase", HttpMethod.POST, authEntity(request), TicketResponse.class).getBody();
    }

    public TicketResponse cancelTicket(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/tickets/" + id + "/cancel", HttpMethod.POST, authEntity(null), TicketResponse.class).getBody();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpEntity<Void> publicEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(headers);
    }

    private <T> HttpEntity<T> authEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private <T> List<T> exchangeList(String url, HttpMethod method, HttpEntity<?> entity,
                                     ParameterizedTypeReference<List<T>> type) {
        return restTemplate.exchange(url, method, entity, type).getBody();
    }
}
