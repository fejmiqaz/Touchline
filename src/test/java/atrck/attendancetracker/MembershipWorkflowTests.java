package atrck.attendancetracker;

import atrck.attendancetracker.model.*;
import atrck.attendancetracker.repository.*;
import atrck.attendancetracker.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.nio.file.*;
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

@SpringBootTest @AutoConfigureMockMvc @Transactional
class MembershipWorkflowTests {
    @Autowired TrackerService tracker;
    @Autowired MembershipService memberships;
    @Autowired MembershipFeeRepository fees;
    @Autowired PlayerRepository players;
    @Autowired GenerationRepository generations;
    @Autowired DemoDataService demo;
    @Autowired MockMvc mvc;
    Generation generation;
    Player player;
    YearMonth month;
    static BigDecimal money(String value) { return new BigDecimal(value); }

    @BeforeEach void setup() {
        generation = tracker.addGeneration("membership-coach", "Academy U14", 2012);
        tracker.addPlayer("membership-coach", generation.getId(), "Alex Player", 7);
        player = tracker.players("membership-coach", generation.getId()).get(0);
        month = YearMonth.from(tracker.today());
    }
    MembershipFee create() {
        return memberships.create("membership-coach", player.getId(), month, money("1500"), money("500"), tracker.today(), "Cash deposit");
    }

    @Test void partialPaymentCanBeCompletedCorrectedAndDeleted() {
        var fee = create();
        assertThat(fee.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(fee.getStatus()).isEqualTo("Partial");
        memberships.update("membership-coach", fee.getId(), money("1500"), money("1500"), tracker.today(), "Paid in full", fee.getRevision());
        fees.flush();
        assertThat(fee.getStatus()).isEqualTo("Paid");
        memberships.update("membership-coach", fee.getId(), money("1500"), money("0"), tracker.today(), "Correction", fee.getRevision());
        assertThat(fee.getStatus()).isEqualTo("Unpaid");
        assertThat(fee.getPaidOn()).isNull();
        memberships.delete("membership-coach", fee.getId(), fee.getRevision(), true);
        fees.flush();
        assertThat(memberships.list("membership-coach", month)).isEmpty();
    }

    @Test void validatesMoneyDatesDuplicatesAndConcurrentEdits() {
        var fee = create();
        assertThatThrownBy(this::create).hasMessageContaining("already has a fee");
        assertThatThrownBy(() -> memberships.update("membership-coach", fee.getId(), money("-1"), money("0"), null, "", fee.getRevision())).hasMessageContaining("non-negative");
        assertThatThrownBy(() -> memberships.update("membership-coach", fee.getId(), money("10.001"), money("0"), null, "", fee.getRevision())).hasMessageContaining("decimal");
        assertThatThrownBy(() -> memberships.update("membership-coach", fee.getId(), money("10"), money("11"), tracker.today(), "", fee.getRevision())).hasMessageContaining("exceed");
        assertThatThrownBy(() -> memberships.update("membership-coach", fee.getId(), money("10"), money("1"), null, "", fee.getRevision())).hasMessageContaining("choose a date");
        assertThatThrownBy(() -> memberships.update("membership-coach", fee.getId(), money("10"), money("1"), tracker.today().plusDays(1), "", fee.getRevision())).hasMessageContaining("choose a date");
        long oldRevision = fee.getRevision();
        memberships.update("membership-coach", fee.getId(), money("1500"), money("700"), tracker.today(), "", oldRevision);
        assertThatThrownBy(() -> memberships.update("membership-coach", fee.getId(), money("1500"), money("800"), tracker.today(), "", oldRevision)).hasMessageContaining("another tab");
        assertThatThrownBy(() -> memberships.delete("membership-coach", fee.getId(), oldRevision, true)).hasMessageContaining("another tab");
        assertThatThrownBy(() -> memberships.delete("membership-coach", fee.getId(), fee.getRevision(), false)).hasMessageContaining("Confirm");
    }

    @Test void isolatesEveryMembershipOperationByCoach() {
        var fee = create();
        assertThat(memberships.list("other", month)).isEmpty();
        assertThatThrownBy(() -> memberships.get("other", fee.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> memberships.history("other", player.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> memberships.create("other", player.getId(), month.plusMonths(1), money("100"), money("0"), null, "")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> memberships.update("other", fee.getId(), money("100"), money("0"), null, "", fee.getRevision())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> memberships.delete("other", fee.getId(), fee.getRevision(), true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void deletingPlayerOrGenerationAlsoRemovesMembershipRecords() {
        var fee = create();
        Long firstId = fee.getId();
        tracker.deletePlayer("membership-coach", player.getId(), true);
        players.flush();
        assertThat(memberships.list("membership-coach", month)).isEmpty();
        tracker.addPlayer("membership-coach", generation.getId(), "Another Player", 10);
        var another = tracker.players("membership-coach", generation.getId()).get(0);
        memberships.create("membership-coach", another.getId(), month, money("100"), money("0"), null, "");
        tracker.deleteGeneration("membership-coach", generation.getId(), true);
        generations.flush();
        assertThat(memberships.list("membership-coach", month)).isEmpty();
    }

    @Test void allWorkspacePagesRenderAndPaymentFormsEnforceCsrf() throws Exception {
        var fee = create();
        var coach = oidcLogin().idToken(t -> t.subject("membership-coach")).authorities(new SimpleGrantedAuthority("ROLE_COACH"));
        for (String path : new String[]{"/", "/training", "/generations", "/attendance", "/memberships", "/memberships/" + fee.getId(), "/players/" + player.getId()}) {
            mvc.perform(get(path).with(coach)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Workspace pages")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\"")));
        }
        mvc.perform(get("/memberships").param("month", "not-a-month").with(coach)).andExpect(status().isBadRequest());
        mvc.perform(post("/memberships/" + fee.getId() + "/edit").with(coach).param("due", "1500").param("paid", "0").param("revision", "1")).andExpect(status().isForbidden());
        mvc.perform(post("/memberships/" + fee.getId() + "/edit").with(coach).with(csrf()).param("due", "1500").param("paid", "0").param("revision", "1")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/memberships/" + fee.getId() + "/delete").with(coach).with(csrf()).param("revision", "2").param("confirmed", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/memberships").with(coach).with(csrf()).param("playerId", player.getId().toString()).param("month", month.toString()).param("due", "1500").param("paid", "0")).andExpect(status().is3xxRedirection());
    }

    @Test void rendersEmptyWorkspaceAndPopulatedPreview() throws Exception {
        var empty = oidcLogin().idToken(t -> t.subject("empty")).authorities(new SimpleGrantedAuthority("ROLE_COACH"));
        for (String path : new String[]{"/", "/training", "/generations", "/attendance", "/memberships"}) {
            mvc.perform(get(path).with(empty)).andExpect(status().isOk());
        }
        demo.seed("preview");
        var roster = memberships.players("preview");
        for (int i = 0; i < 8; i++) memberships.create("preview", roster.get(i).getId(), month, money("1500"),
                i % 3 == 0 ? money("1500") : i % 3 == 1 ? money("500") : money("0"), tracker.today(), "Sample membership");
        var coach = oidcLogin().idToken(t -> t.subject("preview").claim("name", "Coach Preview")).authorities(new SimpleGrantedAuthority("ROLE_COACH"));
        for (String path : new String[]{"/", "/training", "/generations", "/attendance", "/memberships"}) {
            var result = mvc.perform(get(path).with(coach)).andExpect(status().isOk()).andReturn();
            if (Boolean.getBoolean("touchline.preview")) {
                Path folder = Path.of("target", "ui-preview"); Files.createDirectories(folder);
                Files.writeString(folder.resolve(path.equals("/") ? "index.html" : path.substring(1) + ".html"), result.getResponse().getContentAsString());
                Files.copy(Path.of("src/main/resources/static/app.css"), folder.resolve("app.css"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
