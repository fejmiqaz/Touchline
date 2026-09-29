package atrck.attendancetracker.model;

import jakarta.persistence.*;
import java.time.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
public class TrainingSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private Generation generation;
    private LocalDate trainingDate;
    private LocalTime startTime;
    private LocalTime endTime;
    @Column(nullable=false,length=150) private String location;
    @Column(nullable=false,length=500) private String notes;
    private boolean cancelled;
    private boolean recorded;
    private long revision;
    public TrainingSession(Generation generation,LocalDate date,LocalTime start,LocalTime end,String location,String notes) {
        this.generation=generation; this.trainingDate=date; this.startTime=start; this.endTime=end; this.location=location; this.notes=notes;
    }
    public void cancel() { cancelled=true; revision++; }
    public void markRecorded() { recorded=true; revision++; }
    public void clearAttendance() { recorded=false; revision++; }
    public void touch() { revision++; }
    public void update(LocalDate date, LocalTime start, LocalTime end, String location, String notes) {
        trainingDate=date; startTime=start; endTime=end; this.location=location; this.notes=notes; revision++;
    }
}
