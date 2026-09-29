package atrck.attendancetracker;

import atrck.attendancetracker.model.*;
import atrck.attendancetracker.repository.*;
import atrck.attendancetracker.service.*;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CrudTests {
    @Autowired TrackerService tracker;
    @Autowired DemoDataService demo;
    @Autowired GenerationRepository generations;
    @Autowired PlayerRepository players;
    @Autowired TrainingSessionRepository sessions;
    @Autowired PlayerAttendanceRepository attendance;
    @Autowired MockMvc mvc;
    Generation generation;
    TrainingSession saved;
    Player player;

    @BeforeEach void setup() {
        demo.seed("crud-coach");
        generation = tracker.generations("crud-coach").get(0);
        saved = tracker.sessions("crud-coach").stream().filter(s -> s.isRecorded() && s.getGeneration().getId().equals(generation.getId())).findFirst().orElseThrow();
        player = tracker.players("crud-coach", generation.getId()).get(0);
    }

    @Test void editsPreserveAttendanceAndRejectInvalidValues() {
        tracker.updateGeneration("crud-coach", generation.getId(), "Updated generation", 2011);
        tracker.updatePlayer("crud-coach", player.getId(), "Updated player", 88);
        tracker.updateSession("crud-coach", saved.getId(), saved.getTrainingDate(), saved.getStartTime(), saved.getEndTime(), "New pitch", "New notes", saved.getRevision());
        sessions.flush();
        assertThat(tracker.generation("crud-coach", generation.getId()).getName()).isEqualTo("Updated generation");
        assertThat(tracker.player("crud-coach", player.getId()).getShirtNumber()).isEqualTo(88);
        assertThat(tracker.session("crud-coach", saved.getId()).getLocation()).isEqualTo("New pitch");
        assertThat(tracker.playerHistory("crud-coach", player.getId())).hasSize(8);
        assertThatThrownBy(() -> tracker.updatePlayer("crud-coach", player.getId(), "", 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracker.updateGeneration("crud-coach", generation.getId(), "Name", 1800)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracker.updateSession("crud-coach", saved.getId(), tracker.today().plusDays(1), saved.getStartTime(), saved.getEndTime(), "Pitch", "", saved.getRevision())).hasMessageContaining("Recorded training dates");
    }

    @Test void attendanceCanBeDeletedAndRecreatedWithoutDeletingSession() {
        long oldRevision = saved.getRevision();
        tracker.clearAttendance("crud-coach", saved.getId(), oldRevision, true);
        attendance.flush();
        assertThat(attendance.findBySessionIdOrderByPlayerNameAsc(saved.getId())).isEmpty();
        assertThat(saved.isRecorded()).isFalse();
        assertThat(tracker.summaries("crud-coach").stream().filter(s -> s.player().getId().equals(player.getId())).findFirst().orElseThrow().total()).isEqualTo(7);
        assertThatThrownBy(() -> tracker.saveAttendance("crud-coach", saved.getId(), Set.of(), oldRevision)).hasMessageContaining("Another tab");
        tracker.saveAttendance("crud-coach", saved.getId(), Set.of(player.getId()), saved.getRevision());
        assertThat(attendance.findBySessionIdOrderByPlayerNameAsc(saved.getId())).hasSize(12);
        assertThat(tracker.roll("crud-coach", saved.getId()).present()).containsExactly(player.getId());
    }

    @Test void deletingPlayerRemovesOnlyTheirHistoryAndInvalidatesOpenSheets() {
        long revision = saved.getRevision();
        tracker.deletePlayer("crud-coach", player.getId(), true);
        players.flush();
        assertThat(players.findById(player.getId())).isEmpty();
        assertThat(attendance.findByPlayerId(player.getId())).isEmpty();
        assertThat(tracker.roll("crud-coach", saved.getId()).players()).hasSize(11);
        assertThat(saved.getRevision()).isGreaterThan(revision);
        assertThat(tracker.sessions("crud-coach")).hasSize(33);
    }

    @Test void deletingSessionRemovesAttendanceAndRecalculatesAlerts() {
        var flagged = tracker.summaries("crud-coach").stream().filter(s -> s.warning() && s.player().getGeneration().getId().equals(generation.getId())).findFirst().orElseThrow();
        var missed = tracker.playerHistory("crud-coach", flagged.player().getId()).stream().filter(a -> !a.isPresent()).findFirst().orElseThrow().getSession();
        tracker.deleteSession("crud-coach", missed.getId(), missed.getRevision(), true);
        sessions.flush();
        assertThat(sessions.findById(missed.getId())).isEmpty();
        assertThat(attendance.findBySessionIdOrderByPlayerNameAsc(missed.getId())).isEmpty();
        assertThat(tracker.summaries("crud-coach").stream().filter(s -> s.player().getId().equals(flagged.player().getId())).findFirst().orElseThrow().warning()).isFalse();
    }

    @Test void generationDeletionCascadesButLeavesOtherGenerations() {
        tracker.deleteGeneration("crud-coach", generation.getId(), true);
        generations.flush();
        assertThat(generations.findById(generation.getId())).isEmpty();
        assertThat(players.findByGenerationIdOrderByNameAsc(generation.getId())).isEmpty();
        assertThat(tracker.sessions("crud-coach")).hasSize(22);
        assertThat(tracker.summaries("crud-coach")).hasSize(24);
        assertThat(attendance.findByPlayerGenerationOwner("crud-coach")).hasSize(192);
    }

    @Test void allMutationsRejectOtherOwnersAndDeletionRequiresConfirmation() {
        assertThatThrownBy(() -> tracker.updateGeneration("intruder", generation.getId(), "X", 2010)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.updatePlayer("intruder", player.getId(), "X", 1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.updateSession("intruder", saved.getId(), saved.getTrainingDate(), saved.getStartTime(), saved.getEndTime(), "X", "", saved.getRevision())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.deleteGeneration("intruder", generation.getId(), true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.deletePlayer("intruder", player.getId(), true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.deleteSession("intruder", saved.getId(), saved.getRevision(), true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.clearAttendance("intruder", saved.getId(), saved.getRevision(), true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tracker.deleteGeneration("crud-coach", generation.getId(), false)).hasMessageContaining("Confirm");
        assertThatThrownBy(() -> tracker.deletePlayer("crud-coach", player.getId(), false)).hasMessageContaining("Confirm");
        assertThatThrownBy(() -> tracker.deleteSession("crud-coach", saved.getId(), saved.getRevision(), false)).hasMessageContaining("Confirm");
        assertThatThrownBy(() -> tracker.clearAttendance("crud-coach", saved.getId(), saved.getRevision(), false)).hasMessageContaining("Confirm");
    }

    @Test void formsRenderAndPostsEnforceCsrf() throws Exception {
        var coach = oidcLogin().idToken(t -> t.subject("crud-coach")).authorities(new SimpleGrantedAuthority("ROLE_COACH"));
        mvc.perform(get("/players/" + player.getId()).with(coach)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Training history")));
        mvc.perform(get("/generations/" + generation.getId()).with(coach)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Delete generation")));
        mvc.perform(get("/sessions/" + saved.getId()).with(coach)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Delete attendance sheet")));
        mvc.perform(post("/players/" + player.getId() + "/edit").with(coach).param("name", "Edited").param("shirtNumber", "90")).andExpect(status().isForbidden());
        mvc.perform(post("/players/" + player.getId() + "/edit").with(coach).with(csrf()).param("name", "Edited").param("shirtNumber", "90")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/sessions/" + saved.getId() + "/attendance/delete").with(coach).with(csrf()).param("revision", "" + saved.getRevision()).param("confirmed", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/players/" + player.getId() + "/delete").with(coach).with(csrf()).param("confirmed", "true")).andExpect(status().is3xxRedirection());
    }
}
