package atrck.attendancetracker.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
public class Generation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private String owner;
    @Column(nullable=false,length=100) private String name;
    private int birthYear;
    public Generation(String owner,String name,int birthYear) { this.owner=owner; this.name=name; this.birthYear=birthYear; }
    public void update(String name, int birthYear) { this.name = name; this.birthYear = birthYear; }
}
