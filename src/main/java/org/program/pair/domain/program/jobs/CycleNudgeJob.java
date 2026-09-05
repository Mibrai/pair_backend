package org.program.pair.domain.program.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.CycleNudge;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramCycle;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.repository.CycleNudgeRepository;
import org.program.pair.repository.ProgramRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Relance l'auteur d'un programme aux trois moments où son cycle s'arrête.
 *
 * <p>Le cycle a sept états — envie, programme, créneau, publication,
 * inscriptions, séance, clôture. Trois d'entre eux se traversent mal, et ce sont
 * les trois que ce job surveille :
 *
 * <table border="1">
 *   <caption>Les trois déclencheurs</caption>
 *   <tr><th>étape</th><th>fait déclencheur</th></tr>
 *   <tr><td>2</td><td>aucun créneau non annulé, N jours après la création</td></tr>
 *   <tr><td>4</td><td>publié depuis 48 h, aucun inscrit, prochaine séance à plus de 48 h</td></tr>
 *   <tr><td>7</td><td>cycle refermé : plus aucune minute devant soi depuis 24 h</td></tr>
 * </table>
 *
 * <h2>Ce que ce job ne dit jamais : « à venir »</h2>
 *
 * <p>Trois surfaces du dépôt bornent le futur sur le <b>début</b> d'une séance —
 * {@code SlotService.getMySlots}, {@code nextSessionAt}, l'{@code isExpired} de
 * la recherche d'activités — et considèrent donc une séance comme passée à la
 * seconde où elle commence. Une étape 7 écrite sur ce modèle enverrait « ton
 * cycle est bouclé, repose le même » <b>au milieu du cours, à quelqu'un qui y
 * est</b>. C'est la famille de défaut que le client a corrigée trois fois en
 * trois jours.
 *
 * <p>Aucune condition d'ici ne parle de séance à venir. Elles parlent d'horizon —
 * la dernière minute que le programme décrit — et l'horizon d'une séance en
 * cours est dans le futur. Le piège n'est pas évité, il est inexprimable. Voir
 * {@link ProgramCycle}.
 *
 * <h2>Balayage, et non planification</h2>
 *
 * <p>Même raison que {@code ProgramReminderJob} : planifier un envoi par
 * programme obligerait à replanifier à chaque créneau posé, déplacé ou annulé,
 * sur tous les chemins qui touchent un programme — ceux d'aujourd'hui et celui
 * que quelqu'un écrira dans six mois. Le balayage n'a rien à annuler : les
 * conditions vivent dans le {@code WHERE}, et un chemin imprévu se comporte
 * correctement sans le savoir.
 *
 * <h2>Les deux plafonds</h2>
 *
 * <p><b>Par programme</b> : l'index unique {@code (program_id, stage)} de V102.
 * Trois étapes, une ligne par étape au plus — le « trois au maximum sur toute la
 * vie du programme » du contrat n'est pas une vérification qu'on peut oublier
 * d'écrire, c'est une propriété du schéma.
 *
 * <p><b>Par personne</b> : une relance par 48 h, toutes étapes et tous
 * programmes confondus. Elle se compte, et le compte tient à la fois les lignes
 * déjà en base et celles que cette passe vient d'écrire.
 *
 * <p>Les heures de silence s'appliquent : {@code CYCLE_NUDGE} n'est pas dans
 * {@code NotificationType.CRITICAL}, donc {@code PushNotificationService.awake}
 * retient la push jusqu'au réveil. La notification in-app, elle, est écrite dans
 * tous les cas et attend.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CycleNudgeJob {

    /**
     * Profondeur de la fenêtre : au-delà, le fait déclencheur n'est plus frais et
     * la relance n'a plus de sens.
     *
     * <p><b>C'est aussi ce qui évite la salve du premier jour.</b> Sans borne
     * basse, le premier passage du job relancerait d'un coup tout l'historique —
     * chaque programme jamais daté, chaque cycle refermé il y a six mois. Le mode
     * d'échec le plus visible qu'un module de relance puisse avoir, et celui
     * qu'aucune migration de remplissage n'aurait à réparer si la fenêtre est là
     * dès le départ. Même forme que {@code AttendancePromptJob}, qui ne regarde
     * que les séances terminées depuis une à trois heures.
     *
     * <p>Le prix est explicite : un service arrêté plus longtemps que la fenêtre
     * laisse passer les relances de cette période. C'est le compromis retenu deux
     * fois déjà dans ce dépôt, et il est préférable à une salve.
     */
    static final Duration NUDGE_WINDOW = Duration.ofDays(7);

    /** Étape 4 : « publié depuis 48 h » et « prochaine séance à plus de 48 h ». */
    static final Duration PUBLICATION_DELAY = Duration.ofHours(48);

    /** Le plafond glissant par personne, toutes étapes confondues. */
    static final Duration PER_USER_COOLDOWN = Duration.ofHours(48);

    private static final short STAGE_WAITING_FOR_A_DATE = 2;
    private static final short STAGE_PUBLISHED_EMPTY = 4;
    private static final short STAGE_CYCLE_CLOSED = 7;

    private final ProgramRepository programRepository;
    private final CycleNudgeRepository cycleNudgeRepository;
    private final NotificationService notificationService;

    /**
     * Délai avant de relancer un programme qui n'a toujours pas de date, en
     * jours — le {@code N} de l'étape 2.
     *
     * <p><b>Trois, et c'est une mesure.</b> Relevé le 2026-09-06 sur la
     * production : le délai médian entre la création d'un programme et son
     * premier créneau vaut <b>73 secondes</b>, et onze observations organiques
     * sur quatorze sont sous la demi-heure. Le comportement est bimodal — tout de
     * suite, ou jamais — et l'échantillon ne contient personne entre le troisième
     * et le vingt-deuxième jour. À J+3, un programme sans créneau appartient donc
     * presque sûrement à quelqu'un qui s'est arrêté, et non à quelqu'un qui prend
     * son temps : la crainte qui justifiait de mesurer avant de livrer est levée.
     *
     * <p>La mesure repose sur quatorze points, les fixtures de seed ayant dû être
     * écartées — deux tiers de la base. À remesurer vers cent programmes réels.
     *
     * <p><b>Zéro éteint l'étape</b>, et c'est ce qui a permis de livrer le reste
     * du module avant de disposer du chiffre.
     *
     * <p>La fenêtre effective de cette étape est <b>J+3 à J+7</b> : au-delà,
     * {@code ProgramDormancyJob} passe le programme à {@code DORMANT}, et ce
     * balayage-ci ne retient que les {@code ACTIVE}. C'est l'ordre voulu — on
     * prévient, puis on endort.
     */
    @Value("${meetdo.cycle.stage2-delay-days:0}")
    private int stage2DelayDays;

    /**
     * Une passe par heure. La granularité utile est la journée pour l'étape 2 et
     * la demi-journée pour les deux autres : viser plus fin ferait payer un
     * balayage pour un gain que personne ne perçoit, viser plus large ferait
     * arriver « ton cycle est bouclé » deux jours après la séance.
     *
     * <p>À :40, là où {@code AttendancePromptJob} occupe :00 et :15.
     */
    @Scheduled(cron = "0 40 * * * *")
    @Transactional
    public void sendCycleNudges() {
        try {
            Instant now = Instant.now();
            Pass pass = new Pass(now);

            int stage2 = stage2DelayDays > 0
                ? nudge(pass, STAGE_WAITING_FOR_A_DATE, stage2Candidates(now), (p, s) -> true)
                : 0;

            int stage4 = nudge(pass, STAGE_PUBLISHED_EMPTY, stage4Candidates(now),
                (program, schedules) -> hasNoSessionWithin(schedules, now, PUBLICATION_DELAY));

            int stage7 = nudge(pass, STAGE_CYCLE_CLOSED, stage7Candidates(now),
                (program, schedules) -> ProgramCycle.closedBy(ProgramCycle.horizon(schedules), now));

            log.info("Cycle nudge job completed: stage2={} stage4={} stage7={} (stage2 delay={}d)",
                stage2, stage4, stage7, stage2DelayDays);
        } catch (Exception e) {
            // Même posture que les autres jobs : une exécution ratée ne doit pas
            // empêcher la suivante. Rien n'a été marqué pour les programmes non
            // traités, ils restent candidats.
            log.error("Cycle nudge job failed", e);
        }
    }

    private List<UUID> stage2Candidates(Instant now) {
        Instant until = now.minus(Duration.ofDays(stage2DelayDays));
        return programRepository.findStage2Candidates(until.minus(NUDGE_WINDOW), until);
    }

    /**
     * Les candidats de l'étape 4. Deux instants distincts, et les confondre
     * viderait le filtre de son sens : {@code until} regarde en arrière — publié
     * il y a plus de 48 h — quand {@code sessionAfter} regarde en avant — une
     * séance au-delà de 48 h.
     */
    private List<UUID> stage4Candidates(Instant now) {
        Instant until = now.minus(PUBLICATION_DELAY);
        return programRepository.findStage4Candidates(
            until.minus(NUDGE_WINDOW), until, now.plus(PUBLICATION_DELAY));
    }

    /**
     * Les candidats de l'étape 7, présélectionnés sur le dernier <b>début</b>.
     *
     * <p>La borne basse est élargie de la durée conventionnelle d'une séance :
     * l'horizon peut dépasser le dernier début de cette durée-là, donc un
     * programme encore dans la fenêtre par son horizon peut en être sorti par son
     * début. Sans cet élargissement, la présélection serait plus étroite que le
     * prédicat exact et laisserait échapper des programmes dus — le seul sens
     * dans lequel une présélection a le droit de se tromper est l'autre.
     */
    private List<UUID> stage7Candidates(Instant now) {
        Instant until = now.minus(ProgramCycle.CLOSING_GRACE);
        Instant from = until.minus(NUDGE_WINDOW).minus(SlotTiming.DEFAULT_DURATION);
        return programRepository.findStage7Candidates(from, until);
    }

    /** Le prédicat exact d'une étape, appliqué en Java sur les créneaux chargés. */
    @FunctionalInterface
    private interface ExactCondition {
        boolean holds(Program program, Collection<Schedule> schedules);
    }

    private int nudge(Pass pass, short stage, List<UUID> candidateIds, ExactCondition exact) {
        if (candidateIds.isEmpty()) {
            return 0;
        }

        // Deux lectures pour toute l'étape, quel que soit le nombre de candidats :
        // le DTO n'est pas construit ici, seul le contexte de la notification
        // l'est, et il traverse programme → activité → catégorie → auteur.
        List<Program> programs = programRepository.findWithOrganizerDetailsByIds(candidateIds);
        Map<UUID, List<Schedule>> schedulesByProgram = programRepository
            .findSchedulesByProgramIds(candidateIds)
            .stream()
            .collect(Collectors.groupingBy(s -> s.getProgram().getId()));

        List<CycleNudge> written = new ArrayList<>();
        int sent = 0;

        for (Program program : programs) {
            List<Schedule> schedules =
                schedulesByProgram.getOrDefault(program.getId(), List.of());

            if (!exact.holds(program, schedules)) {
                continue;
            }

            var author = program.getUserActivity() != null
                ? program.getUserActivity().getUser()
                : null;
            // Un compte désactivé ne se relance pas : il n'a pas arrêté son cycle,
            // il a quitté le produit.
            if (author == null || !Boolean.TRUE.equals(author.getIsActive())) {
                continue;
            }

            if (!pass.mayNudge(author.getId())) {
                // Plafonné pour cette fenêtre de 48 h. Rien n'est marqué : le
                // programme reste candidat, et la relance repartira plus tard —
                // tant qu'il est encore dans sa fenêtre.
                continue;
            }

            Map<String, Object> payload = NotificationPayload.ofProgram(program)
                // Dupliquée telle quelle dans data côté push : c'est la clé que
                // le routage client lit pour savoir de quelle étape on parle.
                .with("stage", String.valueOf(stage))
                .build();

            // Sans acteur : personne n'a déclenché cette relance, et le
            // destinataire EST l'auteur — passer son propre identifiant ferait
            // interroger le filtre de blocage sur lui-même.
            notificationService.notify(author.getId(), NotificationType.CYCLE_NUDGE, payload);

            written.add(CycleNudge.builder()
                .program(program)
                .user(author)
                .stage(stage)
                .horizon(stage == STAGE_CYCLE_CLOSED ? ProgramCycle.horizon(schedules) : null)
                .sentAt(pass.now())
                .build());
            pass.record(author.getId());
            sent++;
        }

        cycleNudgeRepository.saveAll(written);
        return sent;
    }

    /**
     * Le programme n'a-t-il aucune séance non terminée dans les {@code delay} qui
     * viennent ?
     *
     * <p>Le prédicat exact de l'étape 4. {@code nextUnfinishedStart} rend la
     * séance <b>en cours</b> quand il y en a une, et son début est dans le passé :
     * la condition devient donc fausse pendant une séance, au lieu de devenir
     * vraie en sautant par-dessus elle. C'est toute la différence entre « aucune
     * inscription, dis-en plus » envoyé la veille, et le même message envoyé
     * pendant le cours.
     */
    private static boolean hasNoSessionWithin(Collection<Schedule> schedules,
                                              Instant now, Duration delay) {
        Instant next = ProgramCycle.nextUnfinishedStart(schedules, now);
        return next != null && next.isAfter(now.plus(delay));
    }

    /**
     * L'état d'une passe : ce qu'elle a déjà envoyé, et à qui.
     *
     * <p><b>Le compte en base ne suffit pas.</b>
     * {@code NotificationService.notify} est {@code @Async}, et le registre
     * {@code cycle_nudges} n'est visible qu'au commit de la transaction du job :
     * relire la base au fil de la passe ne montrerait aucune des relances que la
     * passe vient de décider. Sans ce cumul en mémoire, une personne ayant trois
     * programmes dus en recevrait trois d'un coup, et le plafond de 48 h serait
     * une ligne de code qui ne protège de rien.
     */
    private final class Pass {
        private final Instant now;
        private final Instant cooldownSince;
        private final Map<UUID, Boolean> allowedFromDatabase = new HashMap<>();
        private final Set<UUID> nudgedInThisPass = new HashSet<>();

        Pass(Instant now) {
            this.now = now;
            this.cooldownSince = now.minus(PER_USER_COOLDOWN);
        }

        Instant now() {
            return now;
        }

        boolean mayNudge(UUID userId) {
            if (nudgedInThisPass.contains(userId)) {
                return false;
            }
            return allowedFromDatabase.computeIfAbsent(userId, id ->
                cycleNudgeRepository.countByUserIdAndSentAtAfter(id, cooldownSince) == 0);
        }

        void record(UUID userId) {
            nudgedInThisPass.add(userId);
        }
    }
}
