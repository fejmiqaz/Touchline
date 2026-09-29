package atrck.attendancetracker.service;

import atrck.attendancetracker.model.*;
import atrck.attendancetracker.repository.*;
import java.time.*;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicitly invoked sample data import; never runs during ordinary startup. */
@Service
@RequiredArgsConstructor
public class DemoDataService {
    private final GenerationRepository generations;
    private final PlayerRepository players;
    private final TrainingSessionRepository sessions;
    private final PlayerAttendanceRepository attendance;
    private final Clock clock;

    private static final String[][] NAMES = {
        {"Luka Petrov", "Marko Stojanov", "Stefan Ivanov", "David Kostov", "Nikola Trajkov", "Andrej Milanov",
         "Filip Ristov", "Matej Velkov", "Viktor Naumov", "Daniel Iliev", "Leon Pavlov", "Martin Spasov"},
        {"Ana Petrova", "Mila Stojanova", "Sara Ivanova", "Eva Kostova", "Nina Trajkova", "Lara Milanova",
         "Iva Ristova", "Ema Velkova", "Sofia Naumova", "Jana Ilieva", "Lea Pavlova", "Mia Spasova"},
        {"Aleksandar Petrov", "Bojan Stojanov", "Damjan Ivanov", "Elian Kostov", "Goran Trajkov", "Haris Milanov",
         "Ivan Ristov", "Jovan Velkov", "Kristijan Naumov", "Ognjen Iliev", "Pavel Pavlov", "Teodor Spasov"}
    };

    @Transactional
    public int seed(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("A coach's Google subject is required.");
        LocalDate today = LocalDate.now(clock);
        int created = 0;
        for (int group = 0; group < NAMES.length; group++) {
            String name = "Sample Academy · U" + (14 + group * 2);
            if (generations.findByOwnerOrderByBirthYearDescNameAsc(owner).stream().anyMatch(g -> g.getName().equals(name))) continue;
            Generation generation = generations.save(new Generation(owner, name, today.getYear() - 14 - group * 2));
            var roster = new ArrayList<Player>();
            for (int index = 0; index < NAMES[group].length; index++) {
                roster.add(players.save(new Player(generation, NAMES[group][index], index + 1, today.minusDays(40))));
            }
            LocalTime start = LocalTime.of(15 + group * 2, 0);
            for (int training = 0; training < 8; training++) {
                var session = sessions.save(new TrainingSession(generation, today.minusDays(3L * (8 - training)),
                    start, start.plusMinutes(90), "Academy pitch " + (group + 1),
                    "Sample session: " + (training % 2 == 0 ? "Passing, ball control and small-sided games." : "Pressing, transitions and finishing.")));
                for (int index = 0; index < roster.size(); index++) {
                    var record = new PlayerAttendance(session, roster.get(index));
                    // First player triggers the alert, second is at the five-absence boundary.
                    boolean absent = index == 0 ? training < 6 : index == 1 ? training < 5 : (index + training) % 7 == 0;
                    record.setPresent(!absent);
                    attendance.save(record);
                }
                session.markRecorded();
            }
            sessions.save(new TrainingSession(generation, today, start, start.plusMinutes(90), "Academy pitch " + (group + 1),
                "Sample session: click the names of players who are here, then save attendance."));
            sessions.save(new TrainingSession(generation, today.plusDays(1), start, start.plusMinutes(90), "Academy pitch " + (group + 1),
                "Sample session: warm-up, possession drills and match preparation."));
            var cancelled = new TrainingSession(generation, today.minusDays(1), start, start.plusMinutes(90), "Academy pitch " + (group + 1),
                "Sample session: cancelled because of heavy rain. No absences counted.");
            cancelled.cancel();
            sessions.save(cancelled);
            created++;
        }
        return created;
    }
}
