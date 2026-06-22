package org.program.pair.domain.activity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    @NotBlank
    private String name;

    @Column(length = 80)
    private String icon;

    @Column(name = "color_ramp", length = 30)
    private String colorRamp;
}
