package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prévient chaque inscrit qu'une séance a changé d'heure ou de lieu — <b>une fois
 * la modification enregistrée</b>.
 *
 * <p><b>Le défaut fermé ici.</b> Modifier l'heure ou le lieu d'un créneau ne
 * prévenait personne : {@code updateSchedule} écrivait les colonnes et rendait le
 * DTO, sans un appel à {@code notificationService}. {@code SCHEDULE_CHANGED}
 * existait dans l'énumération, était classé critique et destiné à l'e-mail, et
 * n'avait <b>aucun producteur</b>. Un organisateur qui avançait sa séance d'une
 * heure la voyait bouger sur son écran, et trois personnes se présentaient à
 * l'ancienne.
 *
 * <p><b>Pourquoi {@code AFTER_COMMIT} et non un appel direct.</b> Même patron que
 * {@code WatchNotificationListener} et {@code ChatPushListener}, pour la raison
 * qu'{@code ApresCommit} documente longuement : {@code notify} est {@code @Async}
 * et part donc avant le commit. Ici la conséquence serait la plus mauvaise
 * possible — annoncer un nouvel horaire qui n'a pas été enregistré, et faire
 * venir les gens à une heure où il n'y a personne. Rien ne part d'une transaction
 * qui n'a pas abouti.
 *
 * <p><b>{@code REQUIRES_NEW} sur l'écouteur</b>, parce qu'il relit le créneau et
 * ses inscrits : la transaction d'origine est finie, et sans transaction les
 * associations paresseuses du créneau (programme → activité → auteur, que la
 * charge utile porte) ne se chargeraient pas. Aucun risque de la nature de celui
 * qu'{@code ApresCommit} décrit : à cet instant le commit a eu lieu et plus aucun
 * verrou n'est tenu sur la ligne.
 *
 * <p><b>Pas de {@code fallbackExecution = true}</b>, comme chez la veille :
 * {@code ProgramService} est {@code @Transactional} sur la classe, un événement
 * publié hors transaction y est impossible, et une notification silencieusement
 * perdue est un défaut qu'on veut voir en test plutôt qu'un repli qui le
 * masquerait en production.
 *
 * <p><b>Trois modifications en cinq minutes produisent trois notifications.</b>
 * C'est assumé pour l'instant et surveillé : un regroupement par outbox viendra
 * si le cas se voit. Entre-temps, {@code pair.notifications.schedule-changed.enabled}
 * coupe l'émission sans redéploiement.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduleChangeNotificationListener {

    /**
     * Le robinet de retour arrière. Ce type n'avait aucun producteur jusqu'ici :
     * si son arrivée se révélait bruyante — trois envois pour trois retouches
     * d'affilée — il faut pouvoir l'arrêter sans attendre une image.
     */
    @Value("${pair.notifications.schedule-changed.enabled:true}")
    private boolean enabled;

    private final ScheduleRepository scheduleRepository;
    private final SlotConcernedPeople concernedPeople;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onScheduleChanged(ScheduleChangedEvent event) {
        if (!enabled) {
            log.info("Modification du créneau {} non notifiée : émission coupée par configuration",
                event.scheduleId());
            return;
        }

        try {
            Schedule slot = scheduleRepository.findById(event.scheduleId()).orElse(null);
            if (slot == null) {
                // Supprimé entre le commit et ici. Rien à annoncer : la
                // suppression a son propre message.
                return;
            }

            Set<UUID> recipients = concernedPeople.of(slot);
            // L'organisateur sait ce qu'il vient de faire. Se le voir annoncer
            // par e-mail lui apprendrait surtout que nous ne savons pas qui a
            // agi.
            recipients.remove(event.actorId());
            if (recipients.isEmpty()) {
                return;
            }

            // Ce qui a bougé de la séance, et la fin de la série : deux nouvelles
            // distinctes, deux types. Retirer la règle et avancer l'heure d'un
            // même geste envoie les deux.
            if (event.timeChanged() || event.placeChanged()) {
                // Composée une fois pour tous : les valeurs sont les mêmes pour
                // chacun, et rien dedans ne dépend du destinataire.
                Map<String, Object> payload = payloadFor(slot, event);
                for (UUID recipientId : recipients) {
                    notificationService.notify(
                        recipientId, event.actorId(), NotificationType.SCHEDULE_CHANGED, payload);
                }
            }
            if (event.seriesEnded() && finDeSerieAAnnoncer(slot)) {
                Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();
                for (UUID recipientId : recipients) {
                    notificationService.notify(
                        recipientId, event.actorId(), NotificationType.SERIES_ENDED, payload);
                }
            }
        } catch (Exception e) {
            // La modification est enregistrée et commitée : un envoi perdu ne
            // doit pas la faire échouer, et il n'y a plus de transaction à
            // annuler à ce stade.
            log.error("Modification du créneau {} non notifiée : {}",
                event.scheduleId(), e.getMessage(), e);
        }
    }

    /**
     * La fin de série ne s'annonce que si la séance gardée est encore à venir et
     * que le créneau n'est pas annulé.
     *
     * <p>Une séance déjà terminée (la règle retirée pendant les dix minutes où le
     * roulement ne l'a pas encore avancée) n'a plus de « prochaine séance » à
     * garder : annoncer « la séance du 22/09 a bien lieu » pour une séance finie
     * serait faux. Une série annulée, elle, a déjà été annoncée comme telle.
     */
    private boolean finDeSerieAAnnoncer(Schedule slot) {
        return slot.getStatus() != SlotStatus.CANCELLED
            && !SlotTiming.hasEndedBy(slot, java.time.Instant.now());
    }

    /**
     * La charge utile : les nouvelles valeurs, et l'ancienne de ce qui a bougé.
     *
     * <p><b>L'ancienne valeur est le cœur du message.</b> « La séance est
     * modifiée » n'aide personne à décider s'il doit se réorganiser ; « avancée à
     * 18 h, au lieu de 19 h » oui. Le client compose la phrase — il a la langue
     * et le fuseau de l'appareil, que nous n'avons pas ici.
     *
     * <p><b>{@code previousAddress} n'est présente que si l'ancienne adresse était
     * diffusable</b>, et jamais de coordonnées : c'est résolu à la publication de
     * l'événement, quand l'ancien créneau existe encore. Un changement de lieu
     * peut faire passer un créneau de public à privé, et la notification ne doit
     * pas être le chemin par lequel l'adresse qu'on vient de masquer ressort.
     */
    private Map<String, Object> payloadFor(Schedule slot, ScheduleChangedEvent event) {
        // SERIES_ENDED a son propre type : changedFields reste « ce qui a bougé ».
        NotificationPayload payload = NotificationPayload.ofSchedule(slot)
            .with("changedFields", event.changes().stream()
                .filter(c -> c != ScheduleChangedEvent.ScheduleChange.SERIES_ENDED)
                .map(Enum::name).sorted().toList());

        if (event.timeChanged()) {
            payload.with("previousStartsAt", event.previousStartsAt())
                .with("previousEndsAt", event.previousEndsAt());
        }
        if (event.placeChanged()) {
            payload.with("previousPlaceName", event.previousPlaceName())
                .with("previousAddress", event.previousAddress());
        }
        if (event.watchShifted()) {
            // Écrite seulement quand c'est vrai : le client affiche « ta veille a
            // suivi », et une clé à faux ne lui apprendrait rien qu'il doive dire.
            payload.with("watchShifted", true);
        }
        return payload.build();
    }
}
