package atrck.attendancetracker.controller;

import atrck.attendancetracker.service.TrackerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice @RequiredArgsConstructor
public class WorkspaceAdvice {
    private final TrackerService tracker;
    @ModelAttribute
    void common(@AuthenticationPrincipal OidcUser user, Model model, HttpServletRequest request) {
        model.addAttribute("today", tracker.today());
        model.addAttribute("tomorrow", tracker.today().plusDays(1));
        String path = request.getRequestURI();
        String active = path.startsWith("/memberships") ? "memberships" : path.startsWith("/attendance") ? "attendance"
                : path.startsWith("/training") || path.startsWith("/sessions") ? "training"
                : path.startsWith("/generations") || path.startsWith("/players") ? "generations" : "overview";
        model.addAttribute("activePage", active);
        if (user != null) {
            model.addAttribute("coachName", user.getFullName() == null ? "Coach" : user.getFullName());
            model.addAttribute("alerts", tracker.summaries(user.getSubject()).stream().filter(TrackerService.Summary::warning).toList());
        }
    }
}
