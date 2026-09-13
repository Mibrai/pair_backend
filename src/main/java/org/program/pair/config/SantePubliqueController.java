package org.program.pair.config;

import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Santé et version republiées sur le port public quand actuator vit ailleurs
 * (décision §5.1 du 13/09).
 *
 * <p>Sous railway, {@code management.server.port} sépare actuator sur un port
 * privé (P-BA-21, D9 B) : le point de collecte Prometheus n'est lisible que du
 * réseau interne, c'était le but. Mais le même geste avait emporté
 * {@code /actuator/health} et {@code /actuator/info} : plus de sonde de
 * disponibilité pour la plateforme, plus de moyen de savoir quel commit tourne
 * sans passer par le réseau privé.
 *
 * <p>Ce contrôleur ne s'active que si le port de gestion est <b>différent</b> :
 * en dev et en test, actuator répond déjà sur le port principal et cette classe
 * n'existe pas. Il ne rend que ce que les points d'origine rendent en public — le
 * statut seul ({@code show-details=never}), et build-info. Rien de prometheus,
 * rien de la configuration : {@code SecurityConfig} ne permet que ces quatre
 * chemins, et refuse tout le reste d'{@code /actuator/**}.
 */
@RestController
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
public class SantePubliqueController {

    private final HealthEndpoint sante;
    private final InfoEndpoint info;

    public SantePubliqueController(HealthEndpoint sante, InfoEndpoint info) {
        this.sante = sante;
        this.info = info;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, String>> sante() {
        return statut(sante.health());
    }

    @GetMapping("/actuator/health/readiness")
    public ResponseEntity<Map<String, String>> disponibilite() {
        return statut(sante.healthForPath("readiness"));
    }

    @GetMapping("/actuator/health/liveness")
    public ResponseEntity<Map<String, String>> vivacite() {
        return statut(sante.healthForPath("liveness"));
    }

    @GetMapping("/actuator/info")
    public Map<String, Object> info() {
        return info.info();
    }

    /** Même partage que l'actuator d'origine : UP rend 200, tout le reste 503. */
    private static ResponseEntity<Map<String, String>> statut(HealthDescriptor descripteur) {
        if (descripteur == null) {
            return ResponseEntity.notFound().build();
        }
        Status status = descripteur.getStatus();
        HttpStatus code = Status.UP.equals(status) || Status.UNKNOWN.equals(status)
            ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(Map.of("status", status.getCode()));
    }
}
