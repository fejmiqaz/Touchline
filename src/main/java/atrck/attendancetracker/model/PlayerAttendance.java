package atrck.attendancetracker.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
public class PlayerAttendance {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) @JoinColumn(name="session_id") private TrainingSession session;
    @ManyToOne(optional=false) private Player player;
    private boolean present;
    public PlayerAttendance(TrainingSession session,Player player) { this.session=session; this.player=player; }
    public void setPresent(boolean present) { this.present=present; }
}
