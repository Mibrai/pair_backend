package org.program.pair.domain.calendar;

import lombok.RequiredArgsConstructor;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Uid;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotTiming;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.time.Duration;
import java.util.List;

/**
 * Le créneau, tel qu'un agenda le comprend.
 *
 * <p>C'est la seule sortie du produit qui <b>quitte définitivement son
 * périmètre</b>. Un fichier .ics est importé, resynchronisé, parfois partagé
 * entre appareils et entre personnes ; ce qu'on y écrit ne se reprend pas. D'où
 * un principe qui gouverne toute la classe : <b>l'adresse est fournie par
 * l'appelant</b>, jamais résolue ici. C'est à la route de savoir si elle parle à
 * un participant nommé ou au monde entier, et de choisir en conséquence entre
 * la règle qui connaît le demandeur et celle qui l'ignore.
 *
 * <p>ical4j était déjà là — {@code RecurrenceExpander} le lit — mais seulement
 * en lecture. La production de calendrier est nouvelle dans ce dépôt.
 */
@Service
@RequiredArgsConstructor
public class SlotCalendarService {

    /**
     * Deux heures avant, comme le rappel poussé par {@code ProgramReminderJob}.
     * Deux délais différents pour le même rendez-vous donneraient l'impression
     * que l'un des deux s'est trompé.
     */
    private static final Duration REMINDER_BEFORE = Duration.ofHours(2);

    private static final String PROD_ID = "-//meetDo//Créneaux//FR";

    /** Un créneau et son adresse déjà décidée par l'appelant. */
    public record Entry(Schedule slot, String address, String publicUrl) {}

    public String toIcs(List<Entry> entries) {
        Calendar calendar = (Calendar) new Calendar()
            .withDefaults()
            .withProdId(PROD_ID)
            .getFluentTarget();

        for (Entry entry : entries) {
            calendar.add(toEvent(entry));
        }

        StringWriter writer = new StringWriter();
        try {
            new CalendarOutputter(false).output(calendar, writer);
        } catch (Exception e) {
            // La validation d'ical4j est désactivée ci-dessus : ce qui reste
            // relève de l'écriture en mémoire, qui n'échoue pas.
            throw new IllegalStateException("Génération du calendrier impossible", e);
        }
        return writer.toString();
    }

    private VEvent toEvent(Entry entry) {
        Schedule slot = entry.slot();

        VEvent event = new VEvent(
            slot.getStartsAt(),
            SlotTiming.endOf(slot),
            slot.getProgram().getTitle());

        // L'identifiant est stable et dérivé du créneau : réimporter le même
        // fichier met à jour l'événement au lieu d'en créer un second, et un
        // créneau déplacé se replace dans l'agenda au lieu de s'y dédoubler.
        event.add(new Uid(slot.getId() + "@meetdo.fun"));

        // LOCATION ne reçoit que ce que l'appelant a décidé de publier. Le nom du
        // lieu est toujours diffusable ; l'adresse ne s'y ajoute que si elle
        // l'était déjà.
        String location = entry.address() == null || entry.address().isBlank()
            ? slot.getPlaceName()
            : slot.getPlaceName() + ", " + entry.address();
        event.add(new Location(location));

        event.add(new Description(descriptionOf(slot, entry.publicUrl())));

        if (slot.getRecurrenceRule() != null && !slot.getRecurrenceRule().isBlank()) {
            // La règle est stockée sans son préfixe — RecurrenceExpander le retire
            // à la lecture — alors qu'un VEVENT l'attend. ical4j le remet en
            // sérialisant la propriété ; c'est la valeur seule qu'on lui donne.
            event.add(new RRule<>(slot.getRecurrenceRule()));
        }

        event.add(reminder());
        return event;
    }

    /**
     * Le rappel de l'agenda. Déclenché relativement au début, donc il suit le
     * créneau si celui-ci est déplacé — un rappel à heure absolue sonnerait pour
     * un rendez-vous qui a changé d'heure.
     */
    private VAlarm reminder() {
        VAlarm alarm = new VAlarm(REMINDER_BEFORE.negated());
        alarm.add(new Action(Action.VALUE_DISPLAY));
        alarm.add(new Description("Votre créneau meetDo commence dans deux heures"));
        return alarm;
    }

    private String descriptionOf(Schedule slot, String publicUrl) {
        StringBuilder description = new StringBuilder();

        if (slot.getWelcomeNote() != null && !slot.getWelcomeNote().isBlank()) {
            description.append(slot.getWelcomeNote()).append("\n\n");
        }
        if (slot.getCity() != null && !slot.getCity().isBlank()) {
            description.append(slot.getCity()).append("\n");
        }
        if (publicUrl != null) {
            description.append(publicUrl);
        }

        return description.toString().strip();
    }
}
