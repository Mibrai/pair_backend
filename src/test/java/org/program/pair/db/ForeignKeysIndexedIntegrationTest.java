package org.program.pair.db;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-08 — chaque clé étrangère a un index qui la porte <b>en tête</b>.
 *
 * <p>PostgreSQL indexe d'office la colonne <i>référencée</i> d'une clé étrangère,
 * puisque c'est une clé primaire ou une contrainte d'unicité. Il n'indexe
 * <b>jamais</b> la colonne <i>référençante</i>. Or c'est celle-là que l'on
 * interroge : à la lecture quand l'application filtre « les lignes de ce
 * parent », et à la suppression du parent, où PostgreSQL émet lui-même
 * {@code SELECT 1 FROM enfant WHERE fk = ? FOR KEY SHARE} pour appliquer
 * {@code ON DELETE CASCADE}, {@code SET NULL} ou {@code RESTRICT}. Sans index,
 * c'est un parcours séquentiel de la table enfant par ligne parente supprimée —
 * le coût se multiplie, invisible, à chaque suppression de compte.
 *
 * <p><b>Pourquoi « en tête » et pas « quelque part dans l'index »</b> : un index
 * B-tree sur {@code (a, b)} ne sert pas une recherche sur {@code b} seul. La
 * fiche d'audit a trouvé le cas trois fois — clé primaire {@code (conversation_id,
 * user_id)}, unique {@code (owner_id, member_id)}, clé primaire {@code (badge_id,
 * user_id)} : la colonne était bien dans un index, et pourtant inatteignable.
 *
 * <p><b>Ce que ce test accepte comme index</b> : un index valide
 * ({@code indisvalid}) dont la première colonne est celle de la clé, et qui est
 * soit complet, soit partiel de prédicat exactement {@code (colonne IS NOT NULL)}.
 * Cette seconde forme est délibérée : la recherche {@code WHERE fk = ?} implique
 * {@code fk IS NOT NULL}, si bien que le planificateur peut utiliser l'index
 * partiel, et celui-ci ne porte alors que les lignes réellement atteignables.
 * Tout autre prédicat est refusé — un unique partiel comme
 * {@code (user_id, schedule_id) WHERE state NOT IN (…)} ne couvre pas la
 * recherche de la cascade, qui ignore l'état.
 *
 * <p><b>Ce test est le livrable durable de la fiche.</b> La migration V107 ferme
 * les vingt-et-une clés qui manquaient le 12/09 — en {@code CREATE INDEX} simple
 * et non en {@code CONCURRENTLY}, pour les raisons expliquées dans son en-tête ;
 * c'est ce test qui empêche la vingt-deuxième. Il échouera donc sur toute nouvelle table livrée sans index de
 * clé étrangère : ce n'est pas une régression du test, c'est son objet. La
 * réponse attendue est un index dans la migration qui crée la table — pas une
 * ligne de plus dans {@link #EXCEPTIONS}.
 *
 * <p><b>Lecture seule</b> : aucune écriture, aucune fixture, rien qui dépende de
 * l'ordre des classes. La base est partagée par toute la suite ; ce test ne fait
 * qu'interroger le catalogue.
 */
class ForeignKeysIndexedIntegrationTest extends AbstractIntegrationTest {

    /**
     * Les clés étrangères que l'on accepte, nommément, de laisser sans index.
     *
     * <p>La clé de la table est {@code <table>.<nom de la contrainte>}, la valeur
     * est la raison — écrite pour la personne qui relira dans un an, pas pour
     * faire passer le test.
     *
     * <p>Trois conditions doivent tenir <b>ensemble</b> pour qu'une exception
     * soit légitime : la table enfant reste petite et bornée par construction ;
     * la colonne n'est jamais un critère de filtre de l'application ; et la
     * suppression d'une ligne parente est un geste exceptionnel. Si l'une des
     * trois tombe, l'exception doit devenir un index.
     *
     * <p><b>Elle est vide, et ce n'est pas un oubli.</b> Deux candidates ont été
     * examinées le 12/09 et écartées :
     * <ul>
     *   <li>{@code activities.fk_activities_parent} — catalogue fermé de 52
     *       lignes dont la hiérarchie n'est utilisée par aucune ligne. Un index
     *       partiel {@code WHERE parent_id IS NOT NULL} y est littéralement vide,
     *       donc gratuit, et il sera juste le jour où une sous-activité
     *       apparaîtra. Moins cher qu'une exception à surveiller.</li>
     *   <li>{@code schedules.schedules_cancelled_by_fkey} — renseignée sur les
     *       seules séances annulées. Même raisonnement : l'index partiel ne porte
     *       que ces lignes-là.</li>
     * </ul>
     * Le constat général : pour une colonne NULLable, l'index partiel coûte à peu
     * près ce que l'exception prétendait économiser. L'exception ne se justifie
     * vraiment que sur une colonne {@code NOT NULL} d'une table qu'on sait
     * minuscule et jamais filtrée — et il n'y en a aucune aujourd'hui.
     */
    private static final Map<String, String> EXCEPTIONS = new LinkedHashMap<>();

    /**
     * Toute contrainte de clé étrangère de {@code public} dont la première
     * colonne n'est la première colonne d'aucun index utilisable.
     *
     * <p>{@code indkey} est un {@code int2vector} indexé à partir de 0, tandis
     * que {@code conkey} est un tableau indexé à partir de 1 : d'où
     * {@code indkey[0] = conkey[1]}, qui se lit « la première colonne de l'index
     * est la première colonne de la clé ».
     *
     * <p>{@code format('%I', …)} et non une concaténation : {@code pg_get_expr}
     * cite un identifiant exactement quand il en a besoin, et {@code %I} suit la
     * même règle. Les deux textes restent donc comparables si une colonne exige
     * un jour des guillemets.
     */
    private static final String REQUETE_CLES_SANS_INDEX_EN_TETE = """
        SELECT c.conrelid::regclass::text AS nom_table,
               c.conname                  AS nom_contrainte,
               a.attname                  AS premiere_colonne,
               c.confdeltype              AS action_suppression
          FROM pg_constraint c
          JOIN pg_attribute a
            ON a.attrelid = c.conrelid
           AND a.attnum   = c.conkey[1]
         WHERE c.contype = 'f'
           AND c.connamespace = 'public'::regnamespace
           AND NOT EXISTS (
                 SELECT 1
                   FROM pg_index i
                  WHERE i.indrelid  = c.conrelid
                    AND i.indisvalid
                    AND i.indkey[0] = c.conkey[1]
                    AND (i.indpred IS NULL
                         OR pg_get_expr(i.indpred, i.indrelid)
                            = format('(%I IS NOT NULL)', a.attname)))
         ORDER BY 1, 2
        """;

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void chaqueCleEtrangere_doitAvoirUnIndexQuiLaPorteEnTete_quandOnInterrogeLeCatalogue() {
        List<String> aIndexer = clesSansIndexEnTete().stream()
            .filter(cle -> !EXCEPTIONS.containsKey(cle.cle()))
            .map(CleEtrangere::description)
            .toList();

        assertThat(aIndexer)
            .as("""
                Ces clés étrangères n'ont aucun index qui les porte en tête. \
                Chaque suppression de la ligne parente déclenche un parcours \
                séquentiel de la table enfant, et chaque filtre de l'application \
                sur cette colonne aussi. La réponse est un CREATE INDEX dans la \
                migration qui crée la table ; ou, si l'index n'est vraiment pas \
                mérité, une entrée justifiée dans EXCEPTIONS. Ne pas ajouter \
                CONCURRENTLY sans lire l'en-tête de V107 : le verrou de \
                transaction que Flyway prend par défaut le fait attendre \
                indéfiniment, et le conteneur de test ne démarre plus.""")
            .isEmpty();
    }

    /**
     * Une exception qui ne correspond plus à rien est pire qu'une exception : elle
     * donne à croire qu'un arbitrage a été fait, alors que la contrainte a été
     * indexée entre-temps ou a disparu du schéma. Ce test la fait tomber.
     */
    @Test
    void laListeDExceptions_doitResterSansEntreeMorte_quandUneCleEstIndexeeOuSupprimee() {
        List<String> reellementSansIndex = clesSansIndexEnTete().stream()
            .map(CleEtrangere::cle)
            .toList();

        List<String> entreesMortes = EXCEPTIONS.keySet().stream()
            .filter(cle -> !reellementSansIndex.contains(cle))
            .toList();

        assertThat(entreesMortes)
            .as("""
                Ces entrées d'EXCEPTIONS ne désignent plus une clé étrangère sans \
                index : soit elle a reçu son index, soit la contrainte n'existe \
                plus. À retirer de la liste.""")
            .isEmpty();
    }

    /**
     * Un {@code CREATE INDEX CONCURRENTLY} interrompu laisse un index
     * {@code INVALID}, que {@code IF NOT EXISTS} ne recrée pas — il ne regarde que
     * le nom. L'index est alors ignoré du planificateur tout en occupant sa place :
     * la migration repasse en vert, et rien n'est indexé. Le cas s'est produit le
     * 12/09 en éprouvant V107 — {@code idx_conv_members_user} laissé invalide par
     * un essai interrompu — et c'est une des raisons pour lesquelles V107 a
     * finalement renoncé à {@code CONCURRENTLY}. La reprise est
     * {@code DROP INDEX CONCURRENTLY} sur l'index invalide, puis rejeu.
     *
     * <p>Le test garde son sens sans {@code CONCURRENTLY} : il tient la porte pour
     * la première migration qui s'en servira, et un index invalide est de toute
     * façon un index qui ment.
     */
    @Test
    void aucunIndex_neDoitEtreInvalide_quandOnInterrogeLeCatalogue() {
        List<String> invalides = jdbcTemplate.queryForList("""
            SELECT i.indexrelid::regclass::text
              FROM pg_index i
              JOIN pg_class k ON k.oid = i.indexrelid
             WHERE NOT i.indisvalid
               AND k.relnamespace = 'public'::regnamespace
             ORDER BY 1
            """, String.class);

        assertThat(invalides)
            .as("Index invalides : construction interrompue, à reprendre par DROP INDEX CONCURRENTLY puis rejeu")
            .isEmpty();
    }

    private List<CleEtrangere> clesSansIndexEnTete() {
        return jdbcTemplate.query(REQUETE_CLES_SANS_INDEX_EN_TETE, (rs, ligne) -> new CleEtrangere(
            rs.getString("nom_table"),
            rs.getString("nom_contrainte"),
            rs.getString("premiere_colonne"),
            rs.getString("action_suppression")));
    }

    private record CleEtrangere(String table, String contrainte, String colonne, String actionSuppression) {

        String cle() {
            return table + "." + contrainte;
        }

        String description() {
            return "%s (%s, ON DELETE %s)".formatted(cle(), colonne, actionLisible());
        }

        /** {@code confdeltype} de pg_constraint : un seul caractère. */
        private String actionLisible() {
            return switch (actionSuppression) {
                case "a" -> "NO ACTION";
                case "r" -> "RESTRICT";
                case "c" -> "CASCADE";
                case "n" -> "SET NULL";
                case "d" -> "SET DEFAULT";
                default -> actionSuppression;
            };
        }
    }
}
