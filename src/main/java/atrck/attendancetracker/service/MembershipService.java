package atrck.attendancetracker.service;

import atrck.attendancetracker.model.*;
import atrck.attendancetracker.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor @Transactional
public class MembershipService {
    private final MembershipFeeRepository fees;
    private final TrackerService tracker;
    private final PlayerRepository players;

    public List<Player> players(String owner) { return players.findByGenerationOwnerOrderByNameAsc(owner); }
    public List<MembershipFee> list(String owner, YearMonth month) {
        return fees.findByPlayerGenerationOwnerAndFeeMonthOrderByPlayerNameAsc(owner, month.atDay(1));
    }
    public MembershipFee get(String owner, Long id) {
        return fees.findByIdAndPlayerGenerationOwner(id, owner).orElseThrow(MembershipService::missing);
    }
    public List<MembershipFee> history(String owner, Long playerId) {
        tracker.player(owner, playerId);
        return fees.findByPlayerIdOrderByFeeMonthDesc(playerId);
    }
    public MembershipFee create(String owner, Long playerId, YearMonth month, BigDecimal due,
                                BigDecimal paid, LocalDate paidOn, String notes) {
        var player = tracker.player(owner, playerId);
        require(month != null && month.getYear() >= 2000 && month.getYear() <= 2100, "Choose a valid membership month.");
        require(!month.isBefore(YearMonth.from(player.getJoinedOn())), "Membership month cannot precede the player's enrollment month.");
        require(!fees.existsByPlayerIdAndFeeMonth(playerId, month.atDay(1)), "This player already has a fee for that month. Edit the existing record.");
        var fee = new MembershipFee(player, month.atDay(1));
        apply(fee, due, paid, paidOn, notes);
        return fees.save(fee);
    }
    public void update(String owner, Long id, BigDecimal due, BigDecimal paid, LocalDate paidOn, String notes, long revision) {
        var fee = fees.lockOwned(id, owner).orElseThrow(MembershipService::missing);
        revision(fee, revision);
        apply(fee, due, paid, paidOn, notes);
    }
    public void delete(String owner, Long id, long revision, boolean confirmed) {
        var fee = fees.lockOwned(id, owner).orElseThrow(MembershipService::missing);
        require(confirmed, "Confirm deletion of this membership record.");
        revision(fee, revision);
        fees.delete(fee);
    }
    private void apply(MembershipFee fee, BigDecimal due, BigDecimal paid, LocalDate paidOn, String notes) {
        due = money(due); paid = money(paid);
        require(paid.compareTo(due) <= 0, "Amount paid cannot exceed the monthly fee.");
        require(paid.signum() == 0 || (paidOn != null && !paidOn.isAfter(tracker.today()) && !paidOn.isBefore(fee.getPlayer().getJoinedOn())),
                "For a payment, choose a date between enrollment and today.");
        require(notes != null && notes.length() <= 500, "Notes must be at most 500 characters.");
        fee.update(due, paid, paid.signum() == 0 ? null : paidOn, notes.strip());
    }
    private static BigDecimal money(BigDecimal amount) {
        require(amount != null && amount.signum() >= 0 && amount.compareTo(new BigDecimal("9999999999.99")) <= 0,
                "Enter a non-negative MKD amount up to 9,999,999,999.99.");
        try { return amount.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("Use at most two decimal places for MKD amounts."); }
    }
    private static void revision(MembershipFee fee, long revision) {
        require(fee.getRevision() == revision, "This membership record changed in another tab. Reload before saving.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
