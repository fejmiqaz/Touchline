package atrck.attendancetracker.repository;
import atrck.attendancetracker.model.TrainingSession;
import java.util.*;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface TrainingSessionRepository extends JpaRepository<TrainingSession,Long> {
    List<TrainingSession> findByGenerationOwnerOrderByTrainingDateDescStartTimeDesc(String owner);
    List<TrainingSession> findByGenerationOwnerAndTrainingDateAndCancelledFalse(String owner,LocalDate date);
    Optional<TrainingSession> findByIdAndGenerationOwner(Long id,String owner);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TrainingSession s where s.id=:id and s.generation.owner=:owner")
    Optional<TrainingSession> lockOwned(Long id,String owner);
}
