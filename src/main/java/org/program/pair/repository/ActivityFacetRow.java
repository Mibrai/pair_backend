package org.program.pair.repository;

/**
 * Une ligne de {@code browseFacets} : un niveau, et ce qu'il pèse.
 *
 * <p>Projection d'interface, comme {@link ActivityBrowseRow} : le mapping se fait
 * par nom d'alias, et un alias renommé dans la requête casse silencieusement le
 * champ correspondant. Les alias de la requête sont donc à lire avec ceux-ci.
 */
public interface ActivityFacetRow {

    /** Niveau déclaré, ou {@code null} pour une entrée qui n'en déclare aucun. */
    String getLevel();

    long getTotal();

    /** Combien portent une activité que l'appelant pratique. */
    long getMineCount();

    /** Combien l'appelant suit. */
    long getSubscribedCount();
}
