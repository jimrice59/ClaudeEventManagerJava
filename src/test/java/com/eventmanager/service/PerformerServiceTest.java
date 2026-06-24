package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Performer;
import com.eventmanager.repository.PerformerRepository;
import com.eventmanager.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerformerServiceTest {

    @Mock
    private PerformerRepository performerRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private CassandraAsyncWriter cassandraAsyncWriter;

    private PerformerService performerService;

    private Performer performer;
    private PerformerDto performerDto;

    @BeforeEach
    void setUp() {
        performerService = new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter);

        performer = Performer.builder()
                .id(1L)
                .name("The Beatles")
                .genre("Rock")
                .bio("Legendary British band")
                .build();

        performerDto = PerformerDto.builder()
                .id(1L)
                .name("The Beatles")
                .genre("Rock")
                .bio("Legendary British band")
                .build();
    }

    // --- getAllPerformers ---

    @Test
    void getAllPerformers_returnsAllPerformers() {
        when(performerRepository.findAllWithVideos()).thenReturn(List.of(performer));

        List<PerformerDto> result = performerService.getAllPerformers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("The Beatles");
        assertThat(result.get(0).getGenre()).isEqualTo("Rock");
    }

    @Test
    void getAllPerformers_returnsEmptyList() {
        when(performerRepository.findAllWithVideos()).thenReturn(List.of());

        assertThat(performerService.getAllPerformers()).isEmpty();
    }

    // --- getPerformerById ---

    @Test
    void getPerformerById_returnsPerformer() {
        when(performerRepository.findByIdWithVideos(1L)).thenReturn(Optional.of(performer));

        PerformerDto result = performerService.getPerformerById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("The Beatles");
        assertThat(result.getGenre()).isEqualTo("Rock");
        assertThat(result.getBio()).isEqualTo("Legendary British band");
    }

    @Test
    void getPerformerById_throwsWhenNotFound() {
        when(performerRepository.findByIdWithVideos(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> performerService.getPerformerById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Performer")
                .hasMessageContaining("99");
    }

    // --- searchPerformers ---

    @Test
    void searchPerformers_returnsMatchingPerformers() {
        when(performerRepository.findByNameContainingIgnoreCaseWithVideos("beat"))
                .thenReturn(List.of(performer));

        List<PerformerDto> result = performerService.searchPerformers("beat");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("The Beatles");
    }

    @Test
    void searchPerformers_returnsEmptyWhenNoMatch() {
        when(performerRepository.findByNameContainingIgnoreCaseWithVideos("xyz")).thenReturn(List.of());

        assertThat(performerService.searchPerformers("xyz")).isEmpty();
    }

    // --- getPerformersByGenre ---

    @Test
    void getPerformersByGenre_returnsMatchingPerformers() {
        when(performerRepository.findByGenreIgnoreCaseWithVideos("rock")).thenReturn(List.of(performer));

        List<PerformerDto> result = performerService.getPerformersByGenre("rock");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGenre()).isEqualTo("Rock");
    }

    @Test
    void getPerformersByGenre_returnsEmptyWhenNoMatch() {
        when(performerRepository.findByGenreIgnoreCaseWithVideos("jazz")).thenReturn(List.of());

        assertThat(performerService.getPerformersByGenre("jazz")).isEmpty();
    }

    // --- createPerformer ---

    @Test
    void createPerformer_savesToPostgresAndSchedulesCassandraWrite() {
        PerformerDto input = PerformerDto.builder()
                .name("The Beatles").genre("Rock").bio("Legendary British band").build();
        when(performerRepository.save(any(Performer.class))).thenReturn(performer);

        PerformerDto result = performerService.createPerformer(input);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("The Beatles");
        verify(performerRepository).save(any(Performer.class));
        verify(cassandraAsyncWriter).savePerformer(any(CassandraPerformer.class));
    }

    // --- updatePerformer ---

    @Test
    void updatePerformer_updatesAllFieldsAndSchedulesCassandraWrite() {
        PerformerDto update = PerformerDto.builder()
                .name("The Beatles (Remastered)").genre("Classic Rock").bio("Updated bio").build();
        Performer updated = Performer.builder()
                .id(1L).name("The Beatles (Remastered)").genre("Classic Rock").bio("Updated bio").build();
        when(performerRepository.findByIdWithVideos(1L)).thenReturn(Optional.of(performer));
        when(performerRepository.save(any(Performer.class))).thenReturn(updated);

        PerformerDto result = performerService.updatePerformer(1L, update);

        assertThat(result.getName()).isEqualTo("The Beatles (Remastered)");
        assertThat(result.getGenre()).isEqualTo("Classic Rock");
        assertThat(result.getBio()).isEqualTo("Updated bio");
        verify(cassandraAsyncWriter).savePerformer(any(CassandraPerformer.class));
    }

    @Test
    void updatePerformer_throwsWhenNotFound() {
        when(performerRepository.findByIdWithVideos(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> performerService.updatePerformer(99L, performerDto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Performer")
                .hasMessageContaining("99");
        verify(performerRepository, never()).save(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }

    // --- deletePerformer ---

    @Test
    void deletePerformer_deletesFromPostgresAndSchedulesCassandraDelete() {
        when(performerRepository.existsById(1L)).thenReturn(true);

        performerService.deletePerformer(1L);

        verify(performerRepository).deleteById(1L);
        verify(cassandraAsyncWriter).deletePerformer(1L);
    }

    @Test
    void deletePerformer_throwsWhenNotFound() {
        when(performerRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> performerService.deletePerformer(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Performer")
                .hasMessageContaining("99");
        verify(performerRepository, never()).deleteById(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }
}
