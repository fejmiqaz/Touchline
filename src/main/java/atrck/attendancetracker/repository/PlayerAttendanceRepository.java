package atrck.attendancetracker.repository;
import atrck.attendancetracker.model.PlayerAttendance;
import java.util.*;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PlayerAttendanceRepository extends JpaRepository<PlayerAttendance,Long> {
    List<PlayerAttendance> findByPlayerId(Long id);
    void deleteBySessionId(Long id);
    void deleteByPlayerId(Long id);
    List<PlayerAttendance> findBySessionIdOrderByPlayerNameAsc(Long id);
    List<PlayerAttendance> findByPlayerGenerationOwner(String owner);
}
