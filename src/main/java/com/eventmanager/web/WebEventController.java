package com.eventmanager.web;

import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.service.EventService;
import com.eventmanager.service.PerformerService;
import com.eventmanager.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.beans.PropertyEditorSupport;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/ui/events")
@RequiredArgsConstructor
public class WebEventController {

    private static final DateTimeFormatter DATETIME_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final EventService eventService;
    private final VenueService venueService;
    private final PerformerService performerService;

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(LocalDateTime.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text != null && !text.isBlank()) {
                    try {
                        setValue(LocalDateTime.parse(text, DATETIME_LOCAL));
                    } catch (DateTimeParseException e) {
                        setValue(null);
                    }
                } else {
                    setValue(null);
                }
            }

            @Override
            public String getAsText() {
                LocalDateTime v = (LocalDateTime) getValue();
                return v != null ? v.format(DATETIME_LOCAL) : "";
            }
        });
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("events", eventService.getAllEvents());
        return "events/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("event", eventService.getEventById(id));
        return "events/view";
    }

    @GetMapping("/new")
    @PreAuthorize("isAuthenticated()")
    public String newForm(Model model) {
        model.addAttribute("event", new EventRequest());
        populateFormModel(model);
        return "events/form";
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public String create(@Valid @ModelAttribute("event") EventRequest request,
                         BindingResult result, Model model,
                         RedirectAttributes redirectAttrs) {
        if (result.hasErrors()) {
            populateFormModel(model);
            return "events/form";
        }
        if (request.getPerformerIds() == null) {
            request.setPerformerIds(new HashSet<>());
        }
        EventResponse created = eventService.createEvent(request);
        redirectAttrs.addFlashAttribute("successMessage", "Event created successfully.");
        return "redirect:/ui/events/" + created.getId();
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("isAuthenticated()")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("event", toRequest(eventService.getEventById(id)));
        model.addAttribute("eventId", id);
        populateFormModel(model);
        return "events/form";
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("isAuthenticated()")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("event") EventRequest request,
                         BindingResult result, Model model,
                         RedirectAttributes redirectAttrs) {
        if (result.hasErrors()) {
            model.addAttribute("eventId", id);
            populateFormModel(model);
            return "events/form";
        }
        if (request.getPerformerIds() == null) {
            request.setPerformerIds(new HashSet<>());
        }
        eventService.updateEvent(id, request);
        redirectAttrs.addFlashAttribute("successMessage", "Event updated successfully.");
        return "redirect:/ui/events/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttrs) {
        eventService.deleteEvent(id);
        redirectAttrs.addFlashAttribute("successMessage", "Event deleted.");
        return "redirect:/ui/events";
    }

    private void populateFormModel(Model model) {
        model.addAttribute("venues", venueService.getAllVenues());
        model.addAttribute("allPerformers", performerService.getAllPerformers());
    }

    private EventRequest toRequest(EventResponse event) {
        EventRequest r = new EventRequest();
        r.setName(event.getName());
        r.setDescription(event.getDescription());
        r.setEventDate(event.getEventDate());
        r.setTicketPrice(event.getTicketPrice());
        r.setTicketsAvailable(event.getTicketsAvailable());
        r.setVenueId(event.getVenue() != null ? event.getVenue().getId() : null);
        r.setPerformerIds(event.getPerformers() != null
                ? event.getPerformers().stream().map(PerformerDto::getId).collect(Collectors.toSet())
                : new HashSet<>());
        return r;
    }
}
