package atrck.attendancetracker.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
public class Player {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private Generation generation;
    @Column(nullable=false,length=150) private String name;
    private int shirtNumber;
    @Column(nullable=false) private LocalDate joinedOn;
    public Player(Generation generation,String name,int shirtNumber,LocalDate joinedOn) {
        this.generation=generation; this.name=name; this.shirtNumber=shirtNumber; this.joinedOn=joinedOn;
    }
    public void update(String name, int shirtNumber) { this.name = name; this.shirtNumber = shirtNumber; }
}
