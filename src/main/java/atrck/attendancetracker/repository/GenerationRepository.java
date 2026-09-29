package atrck.attendancetracker.repository;
import atrck.attendancetracker.model.Generation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface GenerationRepository extends JpaRepository<Generation,Long> {
    List<Generation> findByOwnerOrderByBirthYearDescNameAsc(String owner);
    Optional<Generation> findByIdAndOwner(Long id,String owner);
}
