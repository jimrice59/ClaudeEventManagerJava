package com.eventmanager.web;

import com.eventmanager.dto.PerformerDto;
import com.eventmanager.service.PerformerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequestMapping("/ui/performers")
@RequiredArgsConstructor
public class WebPerformerController {

    private final PerformerService performerService;

    @GetMapping
    public String list(@RequestParam(required = false) String name,
                       @RequestParam(required = false) String genre,
                       Model model) {
        log.debug("Received request to list performers: name='{}', genre='{}' (web UI)", name, genre);
        if (name != null) {
            model.addAttribute("performers", performerService.searchPerformers(name));
        } else if (genre != null) {
            model.addAttribute("performers", performerService.getPerformersByGenre(genre));
        } else {
            model.addAttribute("performers", performerService.getAllPerformers());
        }
        model.addAttribute("nameFilter", name);
        model.addAttribute("genreFilter", genre);
        return "performers/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        log.debug("Received request to view performer id={} (web UI)", id);
        model.addAttribute("performer", performerService.getPerformerById(id));
        return "performers/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newForm(Model model) {
        log.debug("Received request for new performer form (web UI)");
        model.addAttribute("performer", new PerformerDto());
        return "performers/form";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@Valid @ModelAttribute("performer") PerformerDto dto,
                         BindingResult result, RedirectAttributes redirectAttrs) {
        log.debug("Received request to create performer name='{}' (web UI)", dto.getName());
        if (result.hasErrors()) {
            return "performers/form";
        }
        PerformerDto created = performerService.createPerformer(dto);
        redirectAttrs.addFlashAttribute("successMessage", "Performer created successfully.");
        return "redirect:/ui/performers/" + created.getId();
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editForm(@PathVariable Long id, Model model) {
        log.debug("Received request to edit performer id={} (web UI)", id);
        model.addAttribute("performer", performerService.getPerformerById(id));
        return "performers/form";
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("performer") PerformerDto dto,
                         BindingResult result, RedirectAttributes redirectAttrs) {
        log.debug("Received request to update performer id={} (web UI)", id);
        if (result.hasErrors()) {
            return "performers/form";
        }
        performerService.updatePerformer(id, dto);
        redirectAttrs.addFlashAttribute("successMessage", "Performer updated successfully.");
        return "redirect:/ui/performers/" + id;
    }

    @PostMapping("/{id}/videos/add")
    @PreAuthorize("hasRole('ADMIN')")
    public String addVideo(@PathVariable Long id,
                           @RequestParam String url,
                           RedirectAttributes redirectAttrs) {
        log.debug("Received request to add video to performer id={} (web UI)", id);
        performerService.addVideo(id, url);
        redirectAttrs.addFlashAttribute("successMessage", "Video added.");
        return "redirect:/ui/performers/" + id;
    }

    @PostMapping("/{id}/videos/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteVideo(@PathVariable Long id,
                              @RequestParam String url,
                              RedirectAttributes redirectAttrs) {
        log.debug("Received request to remove video from performer id={} (web UI)", id);
        performerService.deleteVideo(id, url);
        redirectAttrs.addFlashAttribute("successMessage", "Video removed.");
        return "redirect:/ui/performers/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttrs) {
        log.debug("Received request to delete performer id={} (web UI)", id);
        performerService.deletePerformer(id);
        redirectAttrs.addFlashAttribute("successMessage", "Performer deleted.");
        return "redirect:/ui/performers";
    }
}
