package org.program.pair.domain.program;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * L'heure ou le lieu d'une séance vient d'être modifié — <b>et la modification
 * est validée</b>.
 *
 * <p><b>Pourquoi un événement plutôt qu'un appel direct.</b> Exactement la raison
 * de {@code WatchNotificationDemandee} : {@code NotificationService.notify} est
 * {@code @Async}, donc appelée depuis {@code updateSchedule} elle part
 * <b>avant</b> le commit, et rien ne la rattrape s'il n'a pas lieu. Or
 * {@code updateSchedule} peut encore échouer après avoir écrit — le
 * rafraîchissement de la prochaine séance du programme touche une autre ligne, et
 * le contrôle de cohérence des dates peut refuser trois lignes plus bas. On
 * enverrait alors « votre séance est avancée à 18 h » pour un horaire qui n'a
 * jamais été enregistré, et la personne se présenterait à la mauvaise heure sur
 * la foi de notre propre message. Un écouteur {@code AFTER_COMMIT} ferme ce cas :
 * voir {@link ScheduleChangeNotificationListener}.
 *
 * <p><b>L'événement porte l'ancien, jamais le nouveau.</b> Le nouvel état est en
 * base, lisible par l'écouteur, et le recopier ici en ferait une seconde source
 * de vérité qui divergerait le jour où un champ s'ajoute. L'ancien, lui, n'existe
 * plus nulle part une fois la ligne réécrite : c'est la seule chose qu'il faut
 * transporter.
 *
 * <p><b>Pas de coordonnées, ici ni dans la charge utile.</b> {@code previousAddress}
 * ne porte que ce qui était <b>diffusable</b> au sens de
 * {@link SlotAddressVisibility} — l'appelant le résout avant de publier, quand il
 * a encore l'ancien créneau sous la main. Une position exacte partie dans une
 * notification est une position qu'on ne rattrape plus, et le changement de lieu
 * est précisément le geste qui peut faire passer un créneau de public à privé.
 *
 * @param actorId       l'organisateur qui a modifié. Sert deux fois : à ne pas
 *                      lui annoncer sa propre modification, et à laisser le
 *                      filtre de blocage de {@code NotificationService} faire
 *                      son travail.
 * @param watchShifted  vrai si au moins une veille retour a vu son heure limite
 *                      déplacée avec la séance (P-BL-04 étape 5). Le client s'en
 *                      sert pour dire « ta veille a suivi » plutôt que de laisser
 *                      quelqu'un croire qu'il doit réarmer.
 */
public record ScheduleChangedEvent(
    UUID scheduleId,
    UUID actorId,
    Set<ScheduleChange> changes,
    Instant previousStartsAt,
    Instant previousEndsAt,
    String previousPlaceName,
    String previousAddress,
    boolean watchShifted
) {

    /**
     * Ce qui a changé, du point de vue de quelqu'un qui doit s'y rendre.
     *
     * <p>Deux valeurs, et pas une par colonne : la personne n'a pas besoin de
     * savoir que {@code placeType} est passé de {@code PUBLIC} à {@code PRIVATE}
     * — elle a besoin de savoir que le lieu a changé, et d'aller le relire. La
     * granularité fine appartiendrait à un journal d'audit, pas à une
     * notification.
     */
    public enum ScheduleChange {

        /** {@code startsAt} ou {@code endsAt} ne sont plus les mêmes. */
        TIME,

        /** Coordonnées, type de lieu, nom du lieu ou adresse diffusable. */
        PLACE
    }

    /** Vrai si l'heure a bougé — ce qui décide aussi du décalage des veilles. */
    public boolean timeChanged() {
        return changes.contains(ScheduleChange.TIME);
    }

    /** Vrai si le lieu a bougé. */
    public boolean placeChanged() {
        return changes.contains(ScheduleChange.PLACE);
    }
}
