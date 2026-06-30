package com.eventmanager.web;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui/venues")
@RequiredArgsConstructor
public class WebVenueController {

    private final VenueService venueService;

    @GetMapping
    public String list(@RequestParam(required = false) String city, Model model) {
        model.addAttribute("venues",
                city != null ? venueService.getVenuesByCity(city) : venueService.getAllVenues());
        model.addAttribute("cityFilter", city);
        return "venues/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("venue", venueService.getVenueById(id));
        return "venues/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newForm(Model model) {
        model.addAttribute("venue", new VenueDto());
        return "venues/form";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@Valid @ModelAttribute("venue") VenueDto dto,
                         BindingResult result, RedirectAttributes redirectAttrs) {
        if (result.hasErrors()) {
            return "venues/form";
        }
        VenueDto created = venueService.createVenue(dto);
        redirectAttrs.addFlashAttribute("successMessage", "Venue created successfully.");
        return "redirect:/ui/venues/" + created.getId();
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("venue", venueService.getVenueById(id));
        return "venues/form";
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("venue") VenueDto dto,
                         BindingResult result, RedirectAttributes redirectAttrs) {
        if (result.hasErrors()) {
            return "venues/form";
        }
        venueService.updateVenue(id, dto);
        redirectAttrs.addFlashAttribute("successMessage", "Venue updated successfully.");
        return "redirect:/ui/venues/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttrs) {
        venueService.deleteVenue(id);
        redirectAttrs.addFlashAttribute("successMessage", "Venue deleted.");
        return "redirect:/ui/venues";
    }
}
