package com.eventmanager.web;

import com.eventmanager.dto.PerformerDto;
import com.eventmanager.service.PerformerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui/performers")
@RequiredArgsConstructor
public class WebPerformerController {

    private final PerformerService performerService;

    @GetMapping
    public String list(@RequestParam(required = false) String name,
                       @RequestParam(required = false) String genre,
                       Model model) {
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
        model.addAttribute("performer", performerService.getPerformerById(id));
        return "performers/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newForm(Model model) {
        model.addAttribute("performer", new PerformerDto());
        return "performers/form";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@Valid @ModelAttribute("performer") PerformerDto dto,
                         BindingResult result, RedirectAttributes redirectAttrs) {
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
        model.addAttribute("performer", performerService.getPerformerById(id));
        return "performers/form";
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("performer") PerformerDto dto,
                         BindingResult result, RedirectAttributes redirectAttrs) {
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
        performerService.addVideo(id, url);
        redirectAttrs.addFlashAttribute("successMessage", "Video added.");
        return "redirect:/ui/performers/" + id;
    }

    @PostMapping("/{id}/videos/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteVideo(@PathVariable Long id,
                              @RequestParam String url,
                              RedirectAttributes redirectAttrs) {
        performerService.deleteVideo(id, url);
        redirectAttrs.addFlashAttribute("successMessage", "Video removed.");
        return "redirect:/ui/performers/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttrs) {
        performerService.deletePerformer(id);
        redirectAttrs.addFlashAttribute("successMessage", "Performer deleted.");
        return "redirect:/ui/performers";
    }
}
