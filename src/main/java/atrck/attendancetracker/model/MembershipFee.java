package atrck.attendancetracker.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
public class MembershipFee {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Player player;
    @Column(nullable = false) private LocalDate feeMonth;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amountDue;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amountPaid;
    private LocalDate paidOn;
    @Column(nullable = false, length = 500) private String notes;
    private long revision;

    public MembershipFee(Player player, LocalDate month) { this.player = player; this.feeMonth = month; }
    public void update(BigDecimal due, BigDecimal paid, LocalDate paidOn, String notes) {
        this.amountDue = due; this.amountPaid = paid; this.paidOn = paidOn; this.notes = notes; revision++;
    }
    public BigDecimal getBalance() { return amountDue.subtract(amountPaid); }
    public String getStatus() {
        return getBalance().signum() == 0 ? "Paid" : amountPaid.signum() > 0 ? "Partial" : "Unpaid";
    }
}
