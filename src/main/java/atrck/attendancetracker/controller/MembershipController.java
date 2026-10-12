package atrck.attendancetracker.controller;

import atrck.attendancetracker.service.*;
import atrck.attendancetracker.model.MembershipFee;
import java.math.BigDecimal;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequiredArgsConstructor
public class MembershipController {
    private final MembershipService memberships;
    private final TrackerService tracker;

    @GetMapping("/memberships")
    String list(@AuthenticationPrincipal OidcUser user, @RequestParam(required=false) String month, Model model) {
        var selected = month == null || month.isBlank() ? YearMonth.from(tracker.today()) : parseMonth(month);
        var fees = memberships.list(user.getSubject(), selected);
        var players = memberships.players(user.getSubject());
        model.addAttribute("month", selected.toString());
        model.addAttribute("monthLabel", selected.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH)));
        model.addAttribute("fees", fees);
        model.addAttribute("players", players);
        model.addAttribute("unassigned", players.stream().filter(p -> !YearMonth.from(p.getJoinedOn()).isAfter(selected)
                && fees.stream().noneMatch(f -> f.getPlayer().getId().equals(p.getId()))).count());
        model.addAttribute("due", fees.stream().map(MembershipFee::getAmountDue).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("paid", fees.stream().map(MembershipFee::getAmountPaid).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("balance", fees.stream().map(MembershipFee::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
        return "memberships";
    }
    @PostMapping("/memberships")
    String create(@AuthenticationPrincipal OidcUser user, @RequestParam Long playerId, @RequestParam String month,
                  @RequestParam BigDecimal due, @RequestParam BigDecimal paid, @RequestParam(required=false) LocalDate paidOn,
                  @RequestParam(defaultValue="") String notes, RedirectAttributes flash) {
        var fee = memberships.create(user.getSubject(), playerId, parseMonth(month), due, paid, paidOn, notes);
        flash.addFlashAttribute("success", "Monthly membership fee created.");
        return "redirect:/memberships/" + fee.getId();
    }
    @GetMapping("/memberships/{id}")
    String detail(@AuthenticationPrincipal OidcUser user, @PathVariable Long id, Model model) {
        model.addAttribute("fee", memberships.get(user.getSubject(), id)); return "membership";
    }
    @PostMapping("/memberships/{id}/edit")
    String update(@AuthenticationPrincipal OidcUser user, @PathVariable Long id, @RequestParam BigDecimal due,
                  @RequestParam BigDecimal paid, @RequestParam(required=false) LocalDate paidOn,
                  @RequestParam(defaultValue="") String notes, @RequestParam long revision, RedirectAttributes flash) {
        memberships.update(user.getSubject(), id, due, paid, paidOn, notes, revision);
        flash.addFlashAttribute("success", "Membership payment updated."); return "redirect:/memberships/" + id;
    }
    @PostMapping("/memberships/{id}/delete")
    String delete(@AuthenticationPrincipal OidcUser user, @PathVariable Long id, @RequestParam long revision,
                  @RequestParam(defaultValue="false") boolean confirmed, RedirectAttributes flash) {
        var month = YearMonth.from(memberships.get(user.getSubject(), id).getFeeMonth());
        memberships.delete(user.getSubject(), id, revision, confirmed);
        flash.addFlashAttribute("success", "Membership record deleted."); return "redirect:/memberships?month=" + month;
    }
    private static YearMonth parseMonth(String month) {
        try {
            var result = YearMonth.parse(month);
            if (result.getYear() < 2000 || result.getYear() > 2100) throw new IllegalArgumentException("Choose a month between 2000 and 2100.");
            return result;
        } catch (java.time.format.DateTimeParseException error) { throw new IllegalArgumentException("Choose a valid month (YYYY-MM)."); }
    }
}
