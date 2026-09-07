package org.program.pair.domain.affiche;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.recap.SlotRecapOpenedEvent;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.UUID;

/**
 * « Votre affiche est prête », à ceux qui étaient là.
 *
 * <p>Émis à la naissance de la carte-souvenir d'une séance, c'est-à-dire à
 * l'instant où l'affiche devient calculable. Rien ne casse sans cette
 * notification — l'affiche se découvre aussi dans le fil d'accueil et dans la
 * bannière de présence — mais le fil ne touche que les gens qui rouvrent
 * l'application d'eux-mêmes, et la découverte est le ressort entier de la
 * fonctionnalité.
 *
 * <p><b>Au plus une par séance et par personne</b>, et cette garantie n'est pas
 * un compteur : une carte naît une fois et une seule
 * ({@code uq_recap_occurrence}), et l'événement n'est publié qu'à cette
 * naissance-là. Il n'y a donc rien à plafonner, et rien qui puisse dériver.
 *
 * <p><b>Deux personnes n'en reçoivent pas</b>, et il faut les nommer :
 * <ul>
 *   <li><b>celle qui vient de contribuer</b> — elle est dans l'application, sur
 *       cet écran, à cette seconde. Une push lui annonçant ce qu'elle vient de
 *       provoquer se lit comme un défaut ;</li>
 *   <li><b>celle qui confirmera sa présence plus tard</b> — la carte sera déjà
 *       née. Elle retrouve la séance dans {@code /api/recaps/mine} au moment
 *       même où elle confirme, ce qui est le seul instant où elle y pense.
 *       Rattraper ce cas aurait demandé de tenir un registre des envois, donc de
 *       fabriquer l'état que ce module n'a pas.</li>
 * </ul>
 *
 * <p><b>Après commit et hors du fil appelant</b> : la carte doit exister quand
 * la notification part, sans quoi le tap ouvre un écran vide.
 * {@code NotificationService.notify} étant lui-même {@code @Async}, aucun
 * aller-retour ne s'ajoute au temps de réponse de la contribution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AfficheReadyListener {

    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final NotificationService notificationService;

    // REQUIRES_NEW, et non la transaction ambiante : après le commit il n'y en a
    // plus, et Spring refuse d'ailleurs de démarrer avec toute autre propagation
    // sur un écouteur AFTER_COMMIT. Les deux lectures ci-dessous ont donc leur
    // propre transaction, en lecture seule.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onRecapOpened(SlotRecapOpenedEvent event) {
        try {
            Schedule slot = scheduleRepository.findById(event.scheduleId()).orElse(null);
            if (slot == null) {
                return;
            }

            // La charge décrit la SÉANCE vécue, pas la ligne de créneau : sur une
            // série récurrente, celle-ci pointe déjà sur mardi prochain, et
            // ofSchedule() en tirerait une date de souvenir dans le futur.
            Map<String, Object> payload = NotificationPayload.ofSchedule(slot)
                .with("sessionAt", event.occurrenceStart())
                .with("startsAt", event.occurrenceStart())
                // La clé que le client lit pour distinguer deux séances d'un même
                // créneau, du même nom que sur SlotRecapDto et AfficheDto.
                .with("slotStartedAt", event.occurrenceStart())
                .build();

            for (Attendance attendance : attendanceRepository
                    .findByScheduleIdAndAttendedAtAndWasPresentTrue(
                        event.scheduleId(), event.occurrenceStart())) {
                User attendee = attendance.getUser();
                if (attendee == null || !Boolean.TRUE.equals(attendee.getIsActive())) {
                    continue;
                }
                UUID attendeeId = attendee.getId();
                if (attendeeId.equals(event.openedBy())) {
                    continue;
                }
                // Sans acteur : personne n'a « fait » cette notification à
                // quelqu'un d'autre. Passer le contributeur ferait dépendre la
                // découverte de l'affiche du lien entre deux participants, alors
                // qu'elle ne parle que de la séance.
                notificationService.notify(attendeeId, NotificationType.AFFICHE_READY, payload);
            }
        } catch (Exception e) {
            // Une notification perdue coûte une découverte différée, jamais une
            // contribution perdue : la carte est déjà commitée quand on arrive ici.
            log.error("AFFICHE_READY non émise pour le créneau {} : {}",
                event.scheduleId(), e.getMessage());
        }
    }
}
