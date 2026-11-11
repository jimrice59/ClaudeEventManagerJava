package com.eventmanager.controller;

import com.eventmanager.service.EventSearchCriteria;
import com.eventmanager.service.EventService;
import com.eventmanager.service.TicketService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/events parameter handling: routing to the right service method and 400s for bad input. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EventService eventService;

    @MockBean
    private TicketService ticketService;

    @Test
    void noFiltersUsesTheUnfilteredList() throws Exception {
        when(eventService.getAllEvents()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/events")).andExpect(status().isOk());

        verify(eventService).getAllEvents();
        verify(eventService, never()).searchEvents(any());
    }

    @Test
    void allFiltersArePassedThroughCombined() throws Exception {
        when(eventService.searchEvents(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/events")
                        .param("venueId", "1").param("performerId", "2")
                        .param("start", "2026-06-01T00:00:00").param("end", "2026-06-30T23:59:59"))
                .andExpect(status().isOk());

        ArgumentCaptor<EventSearchCriteria> criteria = ArgumentCaptor.forClass(EventSearchCriteria.class);
        verify(eventService).searchEvents(criteria.capture());
        assertThat(criteria.getValue()).isEqualTo(new EventSearchCriteria(1L, 2L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59, 59)));
    }

    @Test
    void startAfterEndIs400() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                        .param("start", "2027-01-01T00:00:00").param("end", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must not be after")));
        verify(eventService, never()).searchEvents(any());
    }

    @Test
    void malformedDateIs400NotA500() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("start", "June-1st"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'start'")));
    }

    @Test
    void nonNumericIdIs400NotA500() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("venueId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'venueId'")));
    }
}
