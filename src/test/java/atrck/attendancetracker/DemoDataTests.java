package atrck.attendancetracker;

import atrck.attendancetracker.service.*;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DemoDataTests {
    @Autowired DemoDataService demo;
    @Autowired TrackerService tracker;

    @Test void seedsOwnedDataOnceAndProvidesUsableAttendance() {
        tracker.addGeneration("sample-coach", "Existing generation", 2010);
        assertThat(demo.seed("sample-coach")).isEqualTo(3);
        assertThat(demo.seed("sample-coach")).isZero();
        assertThat(tracker.generations("sample-coach")).hasSize(4);
        assertThat(tracker.sessions("sample-coach")).hasSize(33);
        assertThat(tracker.summaries("sample-coach")).hasSize(36)
            .allMatch(row -> row.total() == 8);
        assertThat(tracker.summaries("sample-coach").stream().filter(TrackerService.Summary::warning)).hasSize(3);
        assertThat(tracker.generations("another-coach")).isEmpty();
        LocalDate today = tracker.today();
        var session = tracker.sessions("sample-coach").stream().filter(s -> s.getTrainingDate().equals(today)).findFirst().orElseThrow();
        var roster = tracker.roll("sample-coach", session.getId());
        assertThat(roster.players()).hasSize(12);
        var present = roster.players().get(0);
        tracker.saveAttendance("sample-coach", session.getId(), Set.of(present.getId()), session.getRevision());
        assertThat(tracker.roll("sample-coach", session.getId()).present()).containsExactly(present.getId());
    }
}
