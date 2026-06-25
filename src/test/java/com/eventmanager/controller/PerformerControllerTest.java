package com.eventmanager.controller;

import com.eventmanager.dto.PerformerDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.service.PerformerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PerformerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PerformerService performerService;

    private PerformerDto beatles() {
        return PerformerDto.builder()
                .id(1L).name("The Beatles").genre("Rock").bio("Legendary British band").build();
    }

    private PerformerDto radiohead() {
        return PerformerDto.builder()
                .id(2L).name("Radiohead").genre("Alternative").bio("British alternative rock band").build();
    }

    // --- GET /api/performers ---

    @Test
    void getAllPerformers_returnsListWithStatus200() throws Exception {
        when(performerService.getAllPerformers()).thenReturn(List.of(beatles(), radiohead()));

        mockMvc.perform(get("/api/performers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("The Beatles"))
                .andExpect(jsonPath("$[1].name").value("Radiohead"));
    }

    @Test
    void getAllPerformers_filtersByNameParam() throws Exception {
        when(performerService.searchPerformers("beat")).thenReturn(List.of(beatles()));

        mockMvc.perform(get("/api/performers").param("name", "beat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("The Beatles"));

        verify(performerService).searchPerformers("beat");
        verify(performerService, never()).getAllPerformers();
    }

    @Test
    void getAllPerformers_filtersByGenreParam() throws Exception {
        when(performerService.getPerformersByGenre("Rock")).thenReturn(List.of(beatles()));

        mockMvc.perform(get("/api/performers").param("genre", "Rock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].genre").value("Rock"));

        verify(performerService).getPerformersByGenre("Rock");
        verify(performerService, never()).getAllPerformers();
    }

    // --- GET /api/performers/{id} ---

    @Test
    void getPerformerById_returnsPerformerWithStatus200() throws Exception {
        when(performerService.getPerformerById(1L)).thenReturn(beatles());

        mockMvc.perform(get("/api/performers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("The Beatles"))
                .andExpect(jsonPath("$.genre").value("Rock"))
                .andExpect(jsonPath("$.bio").value("Legendary British band"));
    }

    @Test
    void getPerformerById_returns404WhenNotFound() throws Exception {
        when(performerService.getPerformerById(99L))
                .thenThrow(new ResourceNotFoundException("Performer", "id", 99L));

        mockMvc.perform(get("/api/performers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Performer not found with id: '99'"));
    }

    // --- POST /api/performers ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPerformer_returnsCreatedWithStatus201() throws Exception {
        PerformerDto input = PerformerDto.builder().name("The Beatles").genre("Rock").bio("Bio").build();
        when(performerService.createPerformer(any(PerformerDto.class))).thenReturn(beatles());

        mockMvc.perform(post("/api/performers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("The Beatles"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPerformer_returns400WhenNameBlank() throws Exception {
        PerformerDto invalid = PerformerDto.builder().name("").genre("Rock").build();

        mockMvc.perform(post("/api/performers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());

        verify(performerService, never()).createPerformer(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void createPerformer_returns403ForUserRole() throws Exception {
        PerformerDto input = PerformerDto.builder().name("The Beatles").genre("Rock").build();

        mockMvc.perform(post("/api/performers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isForbidden());

        verify(performerService, never()).createPerformer(any());
    }

    @Test
    void createPerformer_returns401WhenUnauthenticated() throws Exception {
        PerformerDto input = PerformerDto.builder().name("The Beatles").genre("Rock").build();

        mockMvc.perform(post("/api/performers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());

        verify(performerService, never()).createPerformer(any());
    }

    // --- PUT /api/performers/{id} ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void updatePerformer_returnsUpdatedWithStatus200() throws Exception {
        PerformerDto update = PerformerDto.builder().name("The Beatles").genre("Classic Rock").bio("Updated").build();
        PerformerDto updated = PerformerDto.builder().id(1L).name("The Beatles").genre("Classic Rock").bio("Updated").build();
        when(performerService.updatePerformer(eq(1L), any(PerformerDto.class))).thenReturn(updated);

        mockMvc.perform(put("/api/performers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").value("Classic Rock"))
                .andExpect(jsonPath("$.bio").value("Updated"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updatePerformer_returns404WhenNotFound() throws Exception {
        PerformerDto update = PerformerDto.builder().name("Ghost").genre("Metal").build();
        when(performerService.updatePerformer(eq(99L), any(PerformerDto.class)))
                .thenThrow(new ResourceNotFoundException("Performer", "id", 99L));

        mockMvc.perform(put("/api/performers/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void updatePerformer_returns403ForUserRole() throws Exception {
        PerformerDto update = PerformerDto.builder().name("The Beatles").genre("Rock").build();

        mockMvc.perform(put("/api/performers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isForbidden());
    }

    // --- DELETE /api/performers/{id} ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePerformer_returnsNoContentWithStatus204() throws Exception {
        doNothing().when(performerService).deletePerformer(1L);

        mockMvc.perform(delete("/api/performers/1"))
                .andExpect(status().isNoContent());

        verify(performerService).deletePerformer(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePerformer_returns404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Performer", "id", 99L))
                .when(performerService).deletePerformer(99L);

        mockMvc.perform(delete("/api/performers/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void deletePerformer_returns403ForUserRole() throws Exception {
        mockMvc.perform(delete("/api/performers/1"))
                .andExpect(status().isForbidden());

        verify(performerService, never()).deletePerformer(any());
    }

    @Test
    void deletePerformer_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(delete("/api/performers/1"))
                .andExpect(status().isUnauthorized());

        verify(performerService, never()).deletePerformer(any());
    }
}
