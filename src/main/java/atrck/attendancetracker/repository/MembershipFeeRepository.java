package atrck.attendancetracker.repository;

import atrck.attendancetracker.model.MembershipFee;
import java.time.LocalDate;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface MembershipFeeRepository extends JpaRepository<MembershipFee, Long> {
    void deleteByPlayerId(Long playerId);
    void deleteByPlayerGenerationId(Long generationId);
    List<MembershipFee> findByPlayerGenerationOwnerAndFeeMonthOrderByPlayerNameAsc(String owner, LocalDate month);
    List<MembershipFee> findByPlayerIdOrderByFeeMonthDesc(Long playerId);
    Optional<MembershipFee> findByIdAndPlayerGenerationOwner(Long id, String owner);
    boolean existsByPlayerIdAndFeeMonth(Long playerId, LocalDate month);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from MembershipFee f where f.id=:id and f.player.generation.owner=:owner")
    Optional<MembershipFee> lockOwned(Long id, String owner);
}
