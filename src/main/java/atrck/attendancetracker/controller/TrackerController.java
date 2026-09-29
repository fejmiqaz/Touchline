package atrck.attendancetracker.controller;

import atrck.attendancetracker.service.TrackerService;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequiredArgsConstructor
public class TrackerController {
    private final TrackerService tracker;
    @Value("${spring.security.oauth2.client.registration.google.client-id}") private String clientId;

    @ModelAttribute void common(@AuthenticationPrincipal OidcUser user,Model model) {
        model.addAttribute("today",tracker.today());
        model.addAttribute("tomorrow",tracker.today().plusDays(1));
        if(user!=null) {
            model.addAttribute("coachName",user.getFullName()==null ? "Coach" : user.getFullName());
            model.addAttribute("alerts",tracker.summaries(user.getSubject()).stream().filter(TrackerService.Summary::warning).toList());
        }
    }
    @GetMapping("/login") String login(Model model) { model.addAttribute("configured",!clientId.equals("unconfigured")); return "login"; }
    @GetMapping("/") String dashboard(@AuthenticationPrincipal OidcUser user,Model model) {
        var owner=user.getSubject();
        var sessions=tracker.sessions(owner);
        model.addAttribute("generations",tracker.generations(owner));
        model.addAttribute("todaySessions",sessions.stream().filter(s -> s.getTrainingDate().equals(tracker.today()) && !s.isCancelled()).sorted(Comparator.comparing(s -> s.getStartTime())).toList());
        model.addAttribute("sessions",sessions);
        model.addAttribute("summaries",tracker.summaries(owner));
        return "dashboard";
    }
    @PostMapping("/generations") String addGeneration(@AuthenticationPrincipal OidcUser user,@RequestParam String name,@RequestParam int birthYear,RedirectAttributes flash) {
        var generation=tracker.addGeneration(user.getSubject(),name,birthYear);
        flash.addFlashAttribute("success","Generation created. Add your players below.");
        return "redirect:/generations/"+generation.getId();
    }
    @GetMapping("/generations/{id}") String generation(@AuthenticationPrincipal OidcUser user,@PathVariable Long id,Model model) {
        model.addAttribute("generation",tracker.generation(user.getSubject(),id));
        model.addAttribute("summaries",tracker.summaries(user.getSubject()).stream().filter(s -> s.player().getGeneration().getId().equals(id)).toList());
        return "generation";
    }
    @PostMapping("/generations/{id}/players") String addPlayer(@AuthenticationPrincipal OidcUser user,@PathVariable Long id,@RequestParam String name,@RequestParam int shirtNumber,RedirectAttributes flash) {
        tracker.addPlayer(user.getSubject(),id,name,shirtNumber); flash.addFlashAttribute("success","Player added."); return "redirect:/generations/"+id;
    }
    @PostMapping("/sessions") String createSession(@AuthenticationPrincipal OidcUser user,@RequestParam Long generationId,@RequestParam LocalDate date,@RequestParam LocalTime start,@RequestParam LocalTime end,@RequestParam String location,@RequestParam(defaultValue="") String notes,RedirectAttributes flash) {
        var session=tracker.createSession(user.getSubject(),generationId,date,start,end,location,notes);
        flash.addFlashAttribute("success","Training scheduled. Attendance opens on the training date.");
        return "redirect:/sessions/"+session.getId();
    }
    @GetMapping("/sessions/{id}") String session(@AuthenticationPrincipal OidcUser user,@PathVariable Long id,Model model) {
        model.addAttribute("roll",tracker.roll(user.getSubject(),id)); return "session";
    }
    @PostMapping("/sessions/{id}/attendance") String attendance(@AuthenticationPrincipal OidcUser user,@PathVariable Long id,@RequestParam(required=false) Set<Long> present,@RequestParam long revision,RedirectAttributes flash) {
        tracker.saveAttendance(user.getSubject(),id,present==null ? Set.of() : present,revision);
        flash.addFlashAttribute("success","Attendance saved. Unselected players are marked absent."); return "redirect:/sessions/"+id;
    }
    @PostMapping("/sessions/{id}/cancel") String cancel(@AuthenticationPrincipal OidcUser user,@PathVariable Long id,RedirectAttributes flash) {
        tracker.cancelSession(user.getSubject(),id); flash.addFlashAttribute("success","Training cancelled. No absences will be counted."); return "redirect:/sessions/"+id;
    }

    @PostMapping("/generations/{id}/edit") String updateGeneration(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam String name, @RequestParam int birthYear, RedirectAttributes flash) {
        tracker.updateGeneration(user.getSubject(), id, name, birthYear);
        flash.addFlashAttribute("success", "Generation updated.");
        return "redirect:/generations/" + id;
    }

    @PostMapping("/generations/{id}/delete") String deleteGeneration(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam(defaultValue="false") boolean confirmed, RedirectAttributes flash) {
        tracker.deleteGeneration(user.getSubject(), id, confirmed);
        flash.addFlashAttribute("success", "Generation and its players, sessions and attendance deleted.");
        return "redirect:/";
    }

    @GetMapping("/players/{id}") String player(@AuthenticationPrincipal OidcUser user, @PathVariable Long id, Model model) {
        model.addAttribute("player", tracker.player(user.getSubject(), id));
        model.addAttribute("history", tracker.playerHistory(user.getSubject(), id));
        model.addAttribute("summaries", tracker.summaries(user.getSubject()).stream().filter(s -> s.player().getId().equals(id)).toList());
        return "player";
    }

    @PostMapping("/players/{id}/edit") String updatePlayer(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam String name, @RequestParam int shirtNumber, RedirectAttributes flash) {
        tracker.updatePlayer(user.getSubject(), id, name, shirtNumber);
        flash.addFlashAttribute("success", "Player updated.");
        return "redirect:/players/" + id;
    }

    @PostMapping("/players/{id}/delete") String deletePlayer(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam(defaultValue="false") boolean confirmed, RedirectAttributes flash) {
        Long generationId = tracker.deletePlayer(user.getSubject(), id, confirmed);
        flash.addFlashAttribute("success", "Player and their attendance history deleted.");
        return "redirect:/generations/" + generationId;
    }

    @PostMapping("/sessions/{id}/edit") String updateSession(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam LocalDate date, @RequestParam LocalTime start, @RequestParam LocalTime end,
            @RequestParam String location, @RequestParam(defaultValue="") String notes, @RequestParam long revision, RedirectAttributes flash) {
        tracker.updateSession(user.getSubject(), id, date, start, end, location, notes, revision);
        flash.addFlashAttribute("success", "Training session updated.");
        return "redirect:/sessions/" + id;
    }

    @PostMapping("/sessions/{id}/delete") String deleteSession(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam long revision, @RequestParam(defaultValue="false") boolean confirmed, RedirectAttributes flash) {
        tracker.deleteSession(user.getSubject(), id, revision, confirmed);
        flash.addFlashAttribute("success", "Training session and its attendance deleted. Totals and alerts updated.");
        return "redirect:/";
    }

    @PostMapping("/sessions/{id}/attendance/delete") String clearAttendance(@AuthenticationPrincipal OidcUser user,
            @PathVariable Long id, @RequestParam long revision, @RequestParam(defaultValue="false") boolean confirmed, RedirectAttributes flash) {
        tracker.clearAttendance(user.getSubject(), id, revision, confirmed);
        flash.addFlashAttribute("success", "Attendance deleted. This session is now pending and no longer counts toward totals.");
        return "redirect:/sessions/" + id;
    }
}
