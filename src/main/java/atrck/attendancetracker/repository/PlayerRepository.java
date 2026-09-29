package atrck.attendancetracker.repository;
import atrck.attendancetracker.model.Player;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PlayerRepository extends JpaRepository<Player,Long> {
    Optional<Player> findByIdAndGenerationOwner(Long id, String owner);
    List<Player> findByGenerationIdOrderByNameAsc(Long id);
    List<Player> findByGenerationOwnerOrderByNameAsc(String owner);
}
