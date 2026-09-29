package atrck.attendancetracker;

import atrck.attendancetracker.model.*;
import atrck.attendancetracker.service.TrackerService;
import atrck.attendancetracker.repository.*;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class FootballWorkflowTests {
    @Autowired TrackerService tracker;
    @Autowired TrainingSessionRepository sessions;
    @Autowired PlayerAttendanceRepository attendance;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    Generation generation;
    Player first;
    Player second;
    final LocalDate base=LocalDate.of(2026,9,28);

    void day(LocalDate date) {
        when(clock.getZone()).thenReturn(ZoneId.of("Europe/Skopje"));
        when(clock.instant()).thenReturn(date.atTime(12,0).atZone(ZoneId.of("Europe/Skopje")).toInstant());
    }
    OidcLoginRequestPostProcessor coach(String owner) { return oidcLogin().idToken(token -> token.subject(owner)).authorities(new SimpleGrantedAuthority("ROLE_COACH")); }
    @BeforeEach void setUp() {
        day(base);
        generation=tracker.addGeneration("coach-a","Academy U15",2012);
        tracker.addPlayer("coach-a",generation.getId(),"Alex Player",7);
        tracker.addPlayer("coach-a",generation.getId(),"Ben Player",10);
        first=tracker.players("coach-a",generation.getId()).get(0);
        second=tracker.players("coach-a",generation.getId()).get(1);
    }
    TrainingSession tomorrow() { return tracker.createSession("coach-a",generation.getId(),tracker.today().plusDays(1),LocalTime.of(18,0),LocalTime.of(19,30),"Main pitch",""); }

    @Test void alertStartsAtSixAndCorrectionsDoNotDuplicateRecords() throws Exception {
        TrainingSession last=null;
        for(int i=0;i<6;i++) {
            day(base.plusDays(i)); last=tomorrow(); day(base.plusDays(i+1));
            tracker.saveAttendance("coach-a",last.getId(),Set.of(first.getId()),0);
            var absent=tracker.summaries("coach-a").stream().filter(s -> s.player().getId().equals(second.getId())).findFirst().orElseThrow();
            assertThat(absent.absent()).isEqualTo(i+1);
            assertThat(absent.warning()).isEqualTo(i==5);
            assertThat(absent.percentage()).isEqualTo(100);
        }
        mvc.perform(get("/").with(coach("coach-a"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("1 attendance alert(s)")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("6 absences (100%)")));
        tracker.saveAttendance("coach-a",last.getId(),Set.of(first.getId(),second.getId()),1);
        var corrected=tracker.summaries("coach-a").stream().filter(s -> s.player().getId().equals(second.getId())).findFirst().orElseThrow();
        assertThat(corrected.total()).isEqualTo(6);
        assertThat(corrected.absent()).isEqualTo(5);
        assertThat(corrected.percentage()).isEqualTo(83);
        assertThat(corrected.warning()).isFalse();
        assertThat(attendance.findBySessionIdOrderByPlayerNameAsc(last.getId())).hasSize(2);
    }
    @Test void tomorrowOnlyFutureBlockedAndUntakenSessionsDoNotCount() {
        assertThatThrownBy(() -> tracker.createSession("coach-a",generation.getId(),base,LocalTime.NOON,LocalTime.of(13,0),"Pitch","")).isInstanceOf(IllegalArgumentException.class);
        var session=tomorrow();
        assertThatThrownBy(() -> tracker.saveAttendance("coach-a",session.getId(),Set.of(),0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tracker.summaries("coach-a")).allMatch(s -> s.total()==0);
        tracker.cancelSession("coach-a",session.getId()); day(base.plusDays(1));
        assertThatThrownBy(() -> tracker.saveAttendance("coach-a",session.getId(),Set.of(),1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tracker.summaries("coach-a")).allMatch(s -> s.total()==0);
    }
    @Test void snapshotsRosterAndRejectsStaleEdits() {
        var session=tomorrow(); day(base.plusDays(1));
        tracker.saveAttendance("coach-a",session.getId(),Set.of(),0);
        tracker.addPlayer("coach-a",generation.getId(),"New Player",15);
        assertThat(tracker.roll("coach-a",session.getId()).players()).hasSize(2);
        assertThatThrownBy(() -> tracker.saveAttendance("coach-a",session.getId(),Set.of(first.getId()),0)).hasMessageContaining("Another tab");
        assertThatThrownBy(() -> tracker.cancelSession("coach-a",session.getId())).hasMessageContaining("saved attendance");
    }
    @Test void rejectsOtherCoachesAndPlayersFromOtherGenerations() {
        var session=tomorrow();
        var other=tracker.addGeneration("coach-b","Other",2011);
        tracker.addPlayer("coach-b",other.getId(),"Other Player",9);
        var otherPlayer=tracker.players("coach-b",other.getId()).get(0);
        assertThatThrownBy(() -> tracker.generation("coach-b",generation.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.session("coach-b",session.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        day(base.plusDays(1));
        assertThatThrownBy(() -> tracker.saveAttendance("coach-a",session.getId(),Set.of(otherPlayer.getId()),0)).hasMessageContaining("belong");
        assertThat(tracker.summaries("coach-b")).allMatch(s -> s.total()==0);
    }
    @Test void rejectsOverlappingTrainingAndAllowsAdjacentSessions() {
        tomorrow();
        assertThatThrownBy(() -> tracker.createSession("coach-a",generation.getId(),base.plusDays(1),LocalTime.of(19,0),LocalTime.of(20,0),"Pitch","")).hasMessageContaining("already have");
        tracker.createSession("coach-a",generation.getId(),base.plusDays(1),LocalTime.of(19,30),LocalTime.of(20,30),"Pitch","");
        assertThat(tracker.sessions("coach-a")).hasSize(2);
    }
    @Test void rendersPagesEnforcesCsrfAndSavesUncheckedPlayersAsAbsent() throws Exception {
        var session=tomorrow();
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Continue with Google")));
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/").with(coach("coach-a"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Academy U15")));
        mvc.perform(get("/generations/"+generation.getId()).with(coach("coach-a"))).andExpect(status().isOk());
        mvc.perform(get("/sessions/"+session.getId()).with(coach("coach-a"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Attendance opens")));
        mvc.perform(get("/sessions/"+session.getId()).with(coach("coach-b"))).andExpect(status().isNotFound());
        day(base.plusDays(1));
        mvc.perform(post("/sessions/"+session.getId()+"/attendance").with(coach("coach-a")).param("revision","0")).andExpect(status().isForbidden());
        mvc.perform(post("/sessions/"+session.getId()+"/attendance").with(coach("coach-a")).with(csrf()).param("revision","0").param("present",first.getId().toString())).andExpect(status().is3xxRedirection());
        assertThat(attendance.findBySessionIdOrderByPlayerNameAsc(session.getId())).filteredOn(a -> !a.isPresent()).extracting(a -> a.getPlayer().getId()).containsExactly(second.getId());
        mvc.perform(get("/sessions/"+session.getId()).with(coach("coach-a"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Attendance saved")));
        mvc.perform(post("/logout").with(coach("coach-a")).with(csrf())).andExpect(redirectedUrl("/login?logout"));
    }
}
