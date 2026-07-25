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

    // Toujours un nom de rampe ("orange-red"), jamais un hexadécimal ni NULL —
    // voir V46 pour la normalisation des données historiques.
    @Column(name = "color_ramp", nullable = false, length = 30)
    private String colorRamp;
}
