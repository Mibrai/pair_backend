package org.program.pair.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Compte les requêtes SQL émises pendant une action, par le journal
 * {@code org.hibernate.SQL} — la méthode de {@code RecapsMineQueryCountIntegrationTest}.
 * Le nombre de requêtes est la seule grandeur qui se transporte d'un conteneur
 * local à la production.
 */
final class SqlCompteur {

    private SqlCompteur() {
    }

    /** Les requêtes émises pendant {@code action}, en texte brut. */
    static List<String> pendant(Runnable action) {
        return pendant(() -> {
            action.run();
            return null;
        }).requetes();
    }

    static <T> Releve<T> pendant(Supplier<T> action) {
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level niveauInitial = sql.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sql.addAppender(appender);
        sql.setLevel(Level.DEBUG);
        try {
            T resultat = action.get();
            return new Releve<>(resultat, appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            sql.setLevel(niveauInitial);
            sql.detachAppender(appender);
            appender.stop();
        }
    }

    /** Combien de ces requêtes lisent la table nommée. */
    static long lisant(List<String> requetes, String table) {
        String motif = " from " + table.toLowerCase(Locale.ROOT) + " ";
        return requetes.stream()
            .map(r -> r.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) + " ")
            .filter(r -> r.contains(motif))
            .count();
    }

    record Releve<T>(T resultat, List<String> requetes) {}
}
