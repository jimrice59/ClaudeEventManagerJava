package com.eventmanager.web;

import com.eventmanager.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Session-authenticated ticket actions for the Thymeleaf UI. There is no admin-only gate here —
 * same as the REST API's TicketController, ownership (not role) is what restricts
 * release/purchase/cancel, enforced inside TicketService. Business-rule failures
 * (IllegalArgumentException for a stale/invalid status, AccessDeniedException for a ticket owned
 * by someone else) are caught here and surfaced as a flash "errorMessage" rather than propagating
 * to GlobalExceptionHandler, which would otherwise render a raw JSON body instead of an HTML page.
 */
@Slf4j
@Controller
@RequestMapping("/ui/tickets")
@RequiredArgsConstructor
public class WebTicketController {

    private final TicketService ticketService;

    @GetMapping("/me")
    public String myTickets(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size,
                            Model model) {
        log.debug("Received request for current user's tickets: page={}, size={} (web UI)", page, size);
        model.addAttribute("tickets", ticketService.getMyTickets(page, size));
        return "tickets/my-tickets";
    }

    @PostMapping("/{id}/reserve")
    public String reserve(@PathVariable Long id, @RequestParam Long eventId, RedirectAttributes redirectAttrs) {
        log.debug("Received request to reserve ticket id={} for event id={} (web UI)", id, eventId);
        try {
            ticketService.reserveTicket(id);
            redirectAttrs.addFlashAttribute("successMessage",
                    "Ticket reserved! Find it under My Tickets to purchase or release it.");
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            log.warn("Reserve failed for ticket id={}: {}", id, ex.getMessage());
            redirectAttrs.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/ui/events/" + eventId;
    }

    @PostMapping("/{id}/release")
    public String release(@PathVariable Long id, RedirectAttributes redirectAttrs) {
        log.debug("Received request to release ticket id={} (web UI)", id);
        try {
            ticketService.releaseTicket(id);
            redirectAttrs.addFlashAttribute("successMessage", "Ticket released.");
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            log.warn("Release failed for ticket id={}: {}", id, ex.getMessage());
            redirectAttrs.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/ui/tickets/me";
    }

    @PostMapping("/{id}/purchase")
    public String purchase(@PathVariable Long id, @RequestParam String userCredentials,
                           RedirectAttributes redirectAttrs) {
        log.debug("Received request to purchase ticket id={} (web UI)", id);
        try {
            ticketService.purchaseTicket(id, userCredentials);
            redirectAttrs.addFlashAttribute("successMessage", "Ticket purchased!");
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            log.warn("Purchase failed for ticket id={}: {}", id, ex.getMessage());
            redirectAttrs.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/ui/tickets/me";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttrs) {
        log.debug("Received request to cancel ticket id={} (web UI)", id);
        try {
            ticketService.cancelTicket(id);
            redirectAttrs.addFlashAttribute("successMessage", "Ticket cancelled.");
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            log.warn("Cancel failed for ticket id={}: {}", id, ex.getMessage());
            redirectAttrs.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/ui/tickets/me";
    }
}
