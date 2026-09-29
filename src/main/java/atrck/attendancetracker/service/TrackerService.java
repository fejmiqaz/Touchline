package atrck.attendancetracker.service;

import atrck.attendancetracker.model.*;
import atrck.attendancetracker.repository.*;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class TrackerService {
    private final GenerationRepository generations;
    private final PlayerRepository players;
    private final TrainingSessionRepository sessions;
    private final PlayerAttendanceRepository attendance;
    private final Clock clock;

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public List<Generation> generations(String owner) {
        return generations.findByOwnerOrderByBirthYearDescNameAsc(owner);
    }

    public Generation generation(String owner, Long id) {
        return generations.findByIdAndOwner(id, owner).orElseThrow(TrackerService::missing);
    }

    public List<Player> players(String owner, Long id) {
        generation(owner, id);
        return players.findByGenerationIdOrderByNameAsc(id);
    }

    public List<TrainingSession> sessions(String owner) {
        return sessions.findByGenerationOwnerOrderByTrainingDateDescStartTimeDesc(owner);
    }

    public TrainingSession session(String owner, Long id) {
        return sessions.findByIdAndGenerationOwner(id, owner).orElseThrow(TrackerService::missing);
    }

    public Generation addGeneration(String owner, String name, int birthYear) {
        require(birthYear >= 1900 && birthYear <= today().getYear(), "Enter a valid birth year.");
        return generations.save(new Generation(owner, clean(name, 100), birthYear));
    }

    public void addPlayer(String owner, Long generationId, String name, int shirtNumber) {
        var generation = generation(owner, generationId);
        require(shirtNumber >= 1 && shirtNumber <= 999, "Shirt number must be between 1 and 999.");
        players.save(new Player(generation, clean(name, 150), shirtNumber, today()));
    }

    public TrainingSession createSession(String owner, Long generationId, LocalDate date, LocalTime start, LocalTime end, String location, String notes) {
        var generation = generation(owner, generationId);
        require(date != null && date.equals(today().plusDays(1)), "Create training the day before: select tomorrow's date.");
        require(start != null && end != null && start.isBefore(end), "End time must be after start time.");
        require(notes != null && notes.length() <= 500, "Notes must be at most 500 characters.");
        boolean overlap = sessions.findByGenerationOwnerAndTrainingDateAndCancelledFalse(owner, date).stream()
                .anyMatch(s -> s.getStartTime().isBefore(end) && start.isBefore(s.getEndTime()));
        require(!overlap, "You already have a training session during that time.");
        return sessions.save(new TrainingSession(generation, date, start, end, clean(location, 150), notes.strip()));
    }

    public void cancelSession(String owner, Long id) {
        var session = sessions.lockOwned(id, owner).orElseThrow(TrackerService::missing);
        require(!session.isRecorded(), "A session with saved attendance cannot be cancelled.");
        session.cancel();
    }

    public Player player(String owner, Long id) {
        return players.findByIdAndGenerationOwner(id, owner).orElseThrow(TrackerService::missing);
    }

    public List<PlayerAttendance> playerHistory(String owner, Long id) {
        player(owner, id);
        return attendance.findByPlayerId(id).stream()
                .sorted(Comparator.comparing((PlayerAttendance a) -> a.getSession().getTrainingDate())
                        .thenComparing(a -> a.getSession().getStartTime()).reversed()).toList();
    }

    public void updateGeneration(String owner, Long id, String name, int birthYear) {
        var generation = generation(owner, id);
        require(birthYear >= 1900 && birthYear <= today().getYear(), "Enter a valid birth year.");
        generation.update(clean(name, 100), birthYear);
    }

    public void updatePlayer(String owner, Long id, String name, int shirtNumber) {
        var player = player(owner, id);
        require(shirtNumber >= 1 && shirtNumber <= 999, "Shirt number must be between 1 and 999.");
        player.update(clean(name, 150), shirtNumber);
    }

    public void updateSession(String owner, Long id, LocalDate date, LocalTime start, LocalTime end,
                              String location, String notes, long revision) {
        var training = sessions.lockOwned(id, owner).orElseThrow(TrackerService::missing);
        checkRevision(training, revision);
        require(date != null, "Choose a training date.");
        require(date.equals(training.getTrainingDate()) || (!training.isRecorded() && date.equals(today().plusDays(1))),
                "Recorded training dates stay fixed. Unrecorded sessions may be rescheduled to tomorrow.");
        require(start != null && end != null && start.isBefore(end), "End time must be after start time.");
        require(notes != null && notes.length() <= 500, "Notes must be at most 500 characters.");
        if (!training.isCancelled()) {
            boolean overlap = sessions.findByGenerationOwnerAndTrainingDateAndCancelledFalse(owner, date).stream()
                    .anyMatch(s -> !s.getId().equals(id) && s.getStartTime().isBefore(end) && start.isBefore(s.getEndTime()));
            require(!overlap, "You already have a training session during that time.");
        }
        training.update(date, start, end, clean(location, 150), notes.strip());
    }

    public void clearAttendance(String owner, Long id, long revision, boolean confirmed) {
        var training = sessions.lockOwned(id, owner).orElseThrow(TrackerService::missing);
        require(confirmed, "Confirm that you want to delete this attendance sheet.");
        checkRevision(training, revision);
        attendance.deleteBySessionId(id);
        training.clearAttendance();
    }

    public void deleteSession(String owner, Long id, long revision, boolean confirmed) {
        var training = sessions.lockOwned(id, owner).orElseThrow(TrackerService::missing);
        require(confirmed, "Confirm that you want to delete this training session and its attendance.");
        checkRevision(training, revision);
        attendance.deleteBySessionId(id);
        attendance.flush();
        sessions.delete(training);
    }

    public Long deletePlayer(String owner, Long id, boolean confirmed) {
        var player = player(owner, id);
        require(confirmed, "Confirm that you want to delete this player and their attendance history.");
        Long generationId = player.getGeneration().getId();
        // Invalidate open attendance forms that still contain the removed player.
        attendance.findByPlayerId(id).stream().map(a -> a.getSession().getId()).distinct().sorted()
                .forEach(sessionId -> sessions.lockOwned(sessionId, owner).orElseThrow(TrackerService::missing).touch());
        attendance.deleteByPlayerId(id);
        attendance.flush();
        players.delete(player);
        return generationId;
    }

    public void deleteGeneration(String owner, Long id, boolean confirmed) {
        var generation = generation(owner, id);
        require(confirmed, "Confirm that you want to delete this generation, its players, sessions and attendance.");
        var trainingSessions = sessions(owner).stream().filter(s -> s.getGeneration().getId().equals(id))
                .sorted(Comparator.comparing(TrainingSession::getId)).toList();
        for (var training : trainingSessions) deleteSession(owner, training.getId(), training.getRevision(), true);
        sessions.flush();
        players.deleteAll(players.findByGenerationIdOrderByNameAsc(id));
        players.flush();
        generations.delete(generation);
    }

    private static void checkRevision(TrainingSession training, long revision) {
        require(training.getRevision() == revision, "Another tab updated this session. Reload it before saving again.");
    }

    public record Roll(TrainingSession session, List<Player> players, Set<Long> present) {
    }

    public Roll roll(String owner, Long id) {
        var session = session(owner, id);
        var rows = attendance.findBySessionIdOrderByPlayerNameAsc(id);
        // Once saved, the roster is a historical snapshot; new players do not alter old sessions.
        var roster = session.isRecorded() ? rows.stream().map(PlayerAttendance::getPlayer).toList()
                : players(owner, session.getGeneration().getId()).stream().filter(p -> !p.getJoinedOn().isAfter(session.getTrainingDate())).toList();
        var present = rows.stream().filter(PlayerAttendance::isPresent).map(a -> a.getPlayer().getId()).collect(Collectors.toSet());
        return new Roll(session, roster, present);
    }

    public void saveAttendance(String owner, Long id, Set<Long> present, long revision) {
        var session = sessions.lockOwned(id, owner).orElseThrow(TrackerService::missing);
        require(!session.isCancelled(), "This training session was cancelled.");
        require(!session.getTrainingDate().isAfter(today()), "Attendance opens on the training date.");
        require(session.getRevision() == revision, "Another tab updated this session. Reload it before saving again.");
        var roll = roll(owner, id);
        require(!roll.players().isEmpty(), "Add players to this generation before saving attendance.");
        var roster = roll.players().stream().map(Player::getId).collect(Collectors.toSet());
        require(roster.containsAll(present), "Selected players must belong to this training session.");
        var existing = attendance.findBySessionIdOrderByPlayerNameAsc(id).stream().collect(Collectors.toMap(a -> a.getPlayer().getId(), a -> a));
        for (var player : roll.players()) {
            var record = existing.getOrDefault(player.getId(), new PlayerAttendance(session, player));
            record.setPresent(present.contains(player.getId()));
            attendance.save(record);
        }
        session.markRecorded();
    }

    public record Summary(Player player, long total, long absent) {
        public long percentage() {
            return total == 0 ? 0 : Math.round(absent * 100.0 / total);
        }

        public boolean warning() {
            return absent > 5;
        }
    }

    public List<Summary> summaries(String owner) {
        var records = attendance.findByPlayerGenerationOwner(owner).stream().collect(Collectors.groupingBy(a -> a.getPlayer().getId()));
        return players.findByGenerationOwnerOrderByNameAsc(owner).stream().map(player -> {
            var rows = records.getOrDefault(player.getId(), List.of());
            return new Summary(player, rows.size(), rows.stream().filter(a -> !a.isPresent()).count());
        }).toList();
    }

    private static String clean(String value, int max) {
        require(value != null && !value.isBlank() && value.strip().length() <= max, "Enter a value of at most " + max + " characters.");
        return value.strip();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
