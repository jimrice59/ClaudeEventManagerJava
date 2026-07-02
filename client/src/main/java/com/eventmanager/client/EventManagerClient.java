package com.eventmanager.client;

import com.eventmanager.client.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    public EventResponse getEvent(Long id) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.GET, publicEntity(), EventResponse.class).getBody();
    }

    public EventResponse createEvent(EventRequest request) {
        return restTemplate.exchange(baseUrl + "/api/v1/events", HttpMethod.POST, authEntity(request), EventResponse.class).getBody();
    }

    public EventResponse updateEvent(Long id, EventRequest request) {
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + id, HttpMethod.PUT, authEntity(request), EventResponse.class).getBody();
    }

    public EventResponse reserveTickets(Long eventId, int count) {
        TicketRequest request = new TicketRequest();
        request.setCount(count);
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + eventId + "/tickets/reserve", HttpMethod.POST, authEntity(request), EventResponse.class).getBody();
    }

    public EventResponse releaseTickets(Long eventId, int count) {
        TicketRequest request = new TicketRequest();
        request.setCount(count);
        return restTemplate.exchange(baseUrl + "/api/v1/events/" + eventId + "/tickets/release", HttpMethod.POST, authEntity(request), EventResponse.class).getBody();
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

    private static RestTemplate defaultRestTemplate() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);
        RestTemplate rt = new RestTemplate();
        rt.getMessageConverters().removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
        rt.getMessageConverters().add(converter);
        return rt;
    }
}
