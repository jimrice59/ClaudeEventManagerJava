package com.eventmanager.service;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.VenueRepository;
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
class VenueServiceTest {

    @Mock
    private VenueRepository venueRepository;

    private VenueService venueService;

    private Venue venue;
    private VenueDto venueDto;

    @BeforeEach
    void setUp() {
        venueService = new VenueService(venueRepository);

        venue = Venue.builder()
                .id(1L)
                .name("Madison Square Garden")
                .address("4 Pennsylvania Plaza")
                .city("New York")
                .state("NY")
                .zipCode("10001")
                .capacity(20000)
                .build();

        venueDto = VenueDto.builder()
                .id(1L)
                .name("Madison Square Garden")
                .address("4 Pennsylvania Plaza")
                .city("New York")
                .state("NY")
                .zipCode("10001")
                .capacity(20000)
                .build();
    }

    // --- getAllVenues ---

    @Test
    void getAllVenues_returnsAllVenues() {
        when(venueRepository.findAll()).thenReturn(List.of(venue));

        List<VenueDto> result = venueService.getAllVenues();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Madison Square Garden");
        assertThat(result.get(0).getCity()).isEqualTo("New York");
        assertThat(result.get(0).getCapacity()).isEqualTo(20000);
    }

    @Test
    void getAllVenues_returnsEmptyList() {
        when(venueRepository.findAll()).thenReturn(List.of());

        assertThat(venueService.getAllVenues()).isEmpty();
    }

    // --- getVenueById ---

    @Test
    void getVenueById_returnsVenue() {
        when(venueRepository.findById(1L)).thenReturn(Optional.of(venue));

        VenueDto result = venueService.getVenueById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Madison Square Garden");
        assertThat(result.getAddress()).isEqualTo("4 Pennsylvania Plaza");
        assertThat(result.getCity()).isEqualTo("New York");
        assertThat(result.getState()).isEqualTo("NY");
        assertThat(result.getZipCode()).isEqualTo("10001");
        assertThat(result.getCapacity()).isEqualTo(20000);
    }

    @Test
    void getVenueById_throwsWhenNotFound() {
        when(venueRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> venueService.getVenueById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Venue")
                .hasMessageContaining("99");
    }

    // --- getVenuesByCity ---

    @Test
    void getVenuesByCity_returnsMatchingVenues() {
        when(venueRepository.findByCityIgnoreCase("New York")).thenReturn(List.of(venue));

        List<VenueDto> result = venueService.getVenuesByCity("New York");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCity()).isEqualTo("New York");
    }

    @Test
    void getVenuesByCity_returnsEmptyWhenNoMatch() {
        when(venueRepository.findByCityIgnoreCase("Chicago")).thenReturn(List.of());

        assertThat(venueService.getVenuesByCity("Chicago")).isEmpty();
    }

    // --- createVenue ---

    @Test
    void createVenue_savesAndReturnsDto() {
        VenueDto input = VenueDto.builder()
                .name("Madison Square Garden").address("4 Pennsylvania Plaza")
                .city("New York").state("NY").zipCode("10001").capacity(20000).build();
        when(venueRepository.save(any(Venue.class))).thenReturn(venue);

        VenueDto result = venueService.createVenue(input);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Madison Square Garden");
        assertThat(result.getCapacity()).isEqualTo(20000);
        verify(venueRepository).save(any(Venue.class));
    }

    // --- updateVenue ---

    @Test
    void updateVenue_updatesAllFields() {
        VenueDto update = VenueDto.builder()
                .name("The Garden").address("4 Penn Plaza")
                .city("New York").state("NY").zipCode("10001").capacity(18000).build();
        Venue updated = Venue.builder()
                .id(1L).name("The Garden").address("4 Penn Plaza")
                .city("New York").state("NY").zipCode("10001").capacity(18000).build();
        when(venueRepository.findById(1L)).thenReturn(Optional.of(venue));
        when(venueRepository.save(any(Venue.class))).thenReturn(updated);

        VenueDto result = venueService.updateVenue(1L, update);

        assertThat(result.getName()).isEqualTo("The Garden");
        assertThat(result.getCapacity()).isEqualTo(18000);
        verify(venueRepository).save(any(Venue.class));
    }

    @Test
    void updateVenue_throwsWhenNotFound() {
        when(venueRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> venueService.updateVenue(99L, venueDto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Venue")
                .hasMessageContaining("99");
        verify(venueRepository, never()).save(any());
    }

    // --- deleteVenue ---

    @Test
    void deleteVenue_deletesWhenExists() {
        when(venueRepository.existsById(1L)).thenReturn(true);

        venueService.deleteVenue(1L);

        verify(venueRepository).deleteById(1L);
    }

    @Test
    void deleteVenue_throwsWhenNotFound() {
        when(venueRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> venueService.deleteVenue(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Venue")
                .hasMessageContaining("99");
        verify(venueRepository, never()).deleteById(any());
    }
}
