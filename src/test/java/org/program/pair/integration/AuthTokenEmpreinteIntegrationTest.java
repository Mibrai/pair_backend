package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.AuthToken;
import org.program.pair.domain.auth.AuthTokenType;
import org.program.pair.domain.auth.EmailVerificationService;
import org.program.pair.domain.auth.ResultatVerification;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P-BS-09 — la base ne garde qu'une empreinte du jeton, et la recherche la
 * retrouve.
 *
 * <p><b>Le défaut fermé.</b> {@code auth_tokens.token} portait la valeur exacte
 * que l'utilisateur reçoit dans son lien. Une lecture de cette table — une
 * sauvegarde, un accès de support, des identifiants de base égarés — rendait
 * utilisables tous les liens en circulation, donc tous les comptes
 * correspondants, sans mot de passe et sans trace applicative. Le code condense
 * désormais la valeur présentée et ne compare plus que des condensats.
 *
 * <p><b>Ce que ces tests surveillent surtout : la compatibilité.</b> Passer à
 * l'empreinte change la façon de <i>retrouver</i> un jeton, et c'est là que se
 * casse un déploiement mal fait. Deux invariants s'en chargent ici —
 * l'empreinte de Java doit valoir au caractère près celle que calcule
 * PostgreSQL, et le rattrapage de V112 doit rendre utilisables les jetons émis
 * avant la migration. Sans le premier, plus aucun lien ne fonctionne ; sans le
 * second, plus aucun lien <i>déjà envoyé</i> ne fonctionne — et cette
 * deuxième panne ne se voit que chez les gens qui attendaient leur e-mail.
 *
 * <p>Aucune configuration de contexte ajoutée : cette classe étend
 * {@link AbstractIntegrationTest} sans un {@code @MockitoBean}, et le repli sans
 * fournisseur suffit — sous le profil de test {@code resend.enabled} est faux,
 * l'envoi ne part pas, et {@code pair.email.journaliser-liens} étant faux lui
 * aussi, rien de sensible n'atteint la sortie.
 *
 * <p>La base est partagée par toute la suite : chaque test crée son propre
 * compte, et le seul {@code UPDATE} écrit ici est borné à la ligne qu'il vient
 * d'insérer.
 */
class AuthTokenEmpreinteIntegrationTest extends AbstractIntegrationTest {

    @Autowired AuthTokenRepository authTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmailVerificationService emailVerificationService;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * Le rattrapage de V112, mot pour mot, plus une borne sur la seule ligne du
     * test : la base est partagée, et aucun {@code UPDATE} d'ici n'a le droit de
     * déborder sur les lignes d'une autre classe.
     */
    private static final String RATTRAPAGE = """
        UPDATE auth_tokens
           SET token_hash = encode(sha256(convert_to(token, 'UTF8')), 'hex')
         WHERE token_hash IS NULL
           AND token IS NOT NULL
           AND token = ?
        """;

    // ------------------------------------------------- ce que la base contient

    @Test
    void laBase_doitGarderLEmpreinteDuJeton_quandUnJetonEstEmis() {
        User user = compte("empreinte");

        String jeton = emailVerificationService.generatePasswordResetToken(user);

        String empreinteEnBase = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM auth_tokens WHERE token = ?", String.class, jeton);
        assertThat(empreinteEnBase)
            .as("la colonne token_hash porte le SHA-256 hexadécimal du jeton")
            .isEqualTo(AuthToken.empreinte(jeton))
            .hasSize(64);
    }

    @Test
    void laRecherche_doitRetrouverLeJeton_quandElleInterrogeLEmpreinte() {
        User user = compte("recherche");

        String jeton = emailVerificationService.generatePasswordResetToken(user);

        Optional<AuthToken> trouve = authTokenRepository.findByTokenHashAndType(
            AuthToken.empreinte(jeton), AuthTokenType.PASSWORD_RESET);
        assertThat(trouve).isPresent();
        assertThat(trouve.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(emailVerificationService.validatePasswordResetToken(jeton))
            .contains(user.getId());
    }

    /**
     * L'invariant dont tout le reste dépend : les deux façons de calculer
     * l'empreinte — celle de Java au moment du clic, celle de PostgreSQL au
     * moment du rattrapage — doivent rendre la même chaîne.
     *
     * <p>Un encodage différent (le jeu de caractères par défaut de la plate-forme
     * au lieu d'UTF-8) ou une casse différente de l'hexadécimal suffirait à ce
     * que le rattrapage écrive des empreintes que le code ne retrouve jamais.
     * Rien dans le comportement observable ne le dirait avant le déploiement :
     * les jetons émis <i>après</i> la migration marcheraient parfaitement.
     */
    @Test
    void lEmpreinteDeJava_doitEgalerCelleDeLaBase_quandLesDeuxCondensentLeMemeJeton() {
        for (String valeur : new String[] {
                UUID.randomUUID().toString(), "", "jeton-accentué-éàü", "0"}) {
            String parLaBase = jdbcTemplate.queryForObject(
                "SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, valeur);

            assertThat(AuthToken.empreinte(valeur))
                .as("empreinte de « %s »", valeur)
                .isEqualTo(parLaBase);
        }
    }

    // ------------------------------------------- la compatibilité du déploiement

    /**
     * Un jeton émis <b>avant</b> la migration reste utilisable — c'est le
     * rattrapage de V112 qui le rend tel, et ce test le prouve en deux temps.
     *
     * <p>La ligne est insérée « à l'ancienne » : la valeur en clair, pas
     * d'empreinte. Le premier temps montre que le code neuf ne la retrouve
     * pas — c'est la panne exacte qu'on aurait livrée en oubliant l'{@code
     * UPDATE} : chaque personne ayant reçu un lien de vérification dans les 24 h
     * précédant le déploiement, et chaque demande de réinitialisation des 30
     * dernières minutes, aurait lu « lien inconnu ». Le second temps rejoue le
     * rattrapage et le lien redevient valable, sans que rien n'ait été renvoyé.
     */
    @Test
    void unJetonEmisAvantLaMigration_doitRedevenirUtilisable_quandLeRattrapageEstRejoue() {
        User user = compte("ancien");
        String vieuxJeton = "jeton-anterieur-" + UUID.randomUUID();
        insererALAncienne(user, vieuxJeton);

        assertThat(emailVerificationService.validatePasswordResetToken(vieuxJeton))
            .as("sans empreinte, le code neuf ne retrouve rien — c'est la panne "
                + "qu'une migration sans rattrapage aurait livrée")
            .isEmpty();

        assertThat(jdbcTemplate.update(RATTRAPAGE, vieuxJeton)).isEqualTo(1);

        assertThat(emailVerificationService.validatePasswordResetToken(vieuxJeton))
            .as("le lien déjà envoyé fonctionne de nouveau, sans renvoi")
            .contains(user.getId());
    }

    /**
     * La colonne en clair est encore écrite, et ce test le dit à voix haute :
     * c'est la fenêtre de retour arrière, pas un oubli.
     *
     * <p>Tant qu'elle porte la valeur, revenir à la version précédente du code —
     * qui ne cherche que par la valeur en clair — ne perd aucun lien émis depuis
     * le déploiement. <b>Ce test est à supprimer au Lot 4</b>, en même temps que
     * le commit qui cesse d'écrire la colonne et la migration qui la supprime ;
     * il rougira alors, et c'est ainsi qu'il désigne le geste attendu.
     */
    @Test
    void laColonneEnClair_doitEncoreEtreEcrite_tantQueLeLot4NEstPasPasse() {
        User user = compte("sursis");

        String jeton = emailVerificationService.generatePasswordResetToken(user);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT token FROM auth_tokens WHERE token_hash = ?",
                String.class, AuthToken.empreinte(jeton)))
            .as("fenêtre de retour arrière de P-BS-09 — à retirer au Lot 4")
            .isEqualTo(jeton);
    }

    // ---------------------------------------------- le chemin complet du clic

    @Test
    void unLienDeVerification_doitVerifierLeCompte_quandLeJetonEstRetrouveParEmpreinte() {
        User user = compte("verification");
        emailVerificationService.sendVerificationEmail(user);
        String jeton = jdbcTemplate.queryForObject("""
            SELECT token FROM auth_tokens
             WHERE user_id = ? AND type = 'EMAIL_VERIFICATION' AND consumed_at IS NULL
            """, String.class, user.getId());

        assertThat(emailVerificationService.verifier(jeton))
            .isEqualTo(ResultatVerification.VERIFIE);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getVerificationStatus())
            .isEqualTo(VerificationStatus.EMAIL_VERIFIED);
    }

    /**
     * Une valeur qui n'est pas un jeton ne se condense pas en un jeton, et une
     * valeur vide ne désigne rien. Aucun de ces cas ne doit lever : la route
     * {@code /v/{token}} est publique, et un robot d'aperçu de lien suffit à
     * l'appeler avec n'importe quoi.
     */
    @Test
    void unJetonInconnuOuVide_neDoitRienTrouverEtNeDoitPasLever() {
        assertThat(emailVerificationService.validatePasswordResetToken(
            "pas-un-jeton-" + UUID.randomUUID())).isEmpty();

        assertThatCode(() -> {
            assertThat(emailVerificationService.verifier(null))
                .isEqualTo(ResultatVerification.INCONNU);
            assertThat(emailVerificationService.verifier(""))
                .isEqualTo(ResultatVerification.INCONNU);
            assertThat(emailVerificationService.verifier("   "))
                .isEqualTo(ResultatVerification.INCONNU);
        }).doesNotThrowAnyException();
    }

    /**
     * Le rattrapage recopié ci-dessus doit rester celui de la migration.
     *
     * <p>Un test qui rejoue une instruction recopiée prouve ce que dit la copie,
     * pas ce que fait la migration : le jour où V112 serait corrigée sans que la
     * copie suive, le test resterait vert en éprouvant du code mort. La copie ne
     * peut pas être un extrait littéral — elle est bornée à la ligne du test, la
     * base étant partagée — d'où cette relecture du fichier livré.
     *
     * <p><b>Et d'où la comparaison instruction par instruction, et non par
     * sous-chaîne.</b> La première version de ce verrou cherchait
     * {@code "ALTER COLUMN token_hash SET NOT NULL"} dans le texte entier du
     * fichier : elle l'a trouvé — dans le commentaire qui explique <i>pourquoi</i>
     * cette contrainte est reportée au Lot 4, et dans le {@code COMMENT ON}
     * qui l'annonce au relecteur de la base. Le test rougissait sur une migration
     * juste, et il aurait aussi bien pu verdir sur une migration fausse dont le
     * commentaire disait la bonne chose. Ce qu'on veut éprouver est ce que V112
     * <b>exécute</b>, jamais ce qu'elle <b>explique</b> : on interroge donc la
     * liste de ses instructions, pas son texte.
     */
    @Test
    void lesInstructionsDeLaMigration_doiventEtreCellesQueLeTestRejoue_quandV112EstRelue() {
        List<String> instructions = instructionsDeV112();

        assertThat(instructions)
            .as("les quatre instructions de V112, lues hors commentaires")
            .containsExactly(
                "ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64)",
                "UPDATE auth_tokens SET token_hash = encode(sha256(convert_to(token, 'UTF8')), 'hex')"
                    + " WHERE token_hash IS NULL AND token IS NOT NULL",
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_tokens_token_hash ON auth_tokens (token_hash)",
                "ALTER TABLE auth_tokens ALTER COLUMN token DROP NOT NULL");

        assertThat(normaliser(RATTRAPAGE))
            .as("le rattrapage rejoué par ce test est celui de la migration, borné "
                + "à sa propre ligne — et rien d'autre")
            .isEqualTo(instructionUnique("UPDATE auth_tokens") + " AND token = ?");

        assertThat(instructions)
            .as("le NOT NULL de token_hash est une opération du Lot 4, pas de celle-ci "
                + "— le poser ici ferait échouer toute insertion après un retour arrière")
            .noneMatch(instruction -> instruction.contains("token_hash SET NOT NULL"));
    }

    // ------------------------------------------------------------------ outils

    /**
     * Les instructions de V112, une par entrée, espaces normalisés — et rien de
     * ce qui n'est que du texte.
     *
     * <p>Trois formes de commentaire sont écartées, parce que les trois
     * contiennent des phrases qui parlent du SQL et que toute recherche de
     * sous-chaîne s'y prend : les lignes {@code --}, les blocs
     * {@code /* … *}{@code /} (aucun aujourd'hui, la porte est tenue), et les
     * {@code COMMENT ON} — de la documentation elle aussi, simplement stockée
     * dans la base plutôt qu'en tête de fichier. Ces derniers portent jusqu'au
     * nom de la contrainte du Lot 4 dans leurs littéraux, et des {@code ;} au
     * milieu de leurs phrases : ils sont retirés avant le découpage, pas après.
     */
    private static List<String> instructionsDeV112() {
        String sansBlocs = lire("db/migration/V112__auth_tokens_empreinte.sql")
            .replaceAll("(?s)/\\*.*?\\*/", " ");

        List<String> lignes = sansBlocs.lines()
            .map(String::strip)
            .filter(ligne -> !ligne.isEmpty())
            .filter(ligne -> !ligne.startsWith("--"))
            .toList();

        int documentation = lignes.size();
        for (int i = 0; i < lignes.size(); i++) {
            if (lignes.get(i).toUpperCase(Locale.ROOT).startsWith("COMMENT ON")) {
                documentation = i;
                break;
            }
        }
        List<String> executees = lignes.subList(0, documentation);

        return Arrays.stream(String.join(" ", executees).split(";"))
            .map(AuthTokenEmpreinteIntegrationTest::normaliser)
            .filter(instruction -> !instruction.isEmpty())
            .toList();
    }

    /** L'unique instruction de V112 qui commence ainsi — zéro ou deux est un échec. */
    private static String instructionUnique(String debut) {
        List<String> correspondantes = instructionsDeV112().stream()
            .filter(instruction -> instruction.startsWith(debut))
            .toList();

        assertThat(correspondantes)
            .as("les instructions de V112 commençant par « %s »", debut)
            .hasSize(1);
        return correspondantes.get(0);
    }

    private static String normaliser(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private static String lire(String ressource) {
        try (var flux = new ClassPathResource(ressource).getInputStream()) {
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException absente) {
            throw new IllegalStateException(ressource + " est introuvable sur le classpath", absente);
        }
    }

    /**
     * Une ligne telle que le code d'avant P-BS-09 l'écrivait : valeur en clair,
     * pas d'empreinte. Écrite en SQL et non par le dépôt, précisément parce que
     * l'entité refuse désormais d'enregistrer un jeton sans empreinte.
     */
    private void insererALAncienne(User user, String jetonEnClair) {
        jdbcTemplate.update("""
            INSERT INTO auth_tokens (id, token, token_hash, user_id, type, expires_at, created_at)
            VALUES (?, ?, NULL, ?, 'PASSWORD_RESET', ?, ?)
            """,
            UUID.randomUUID(), jetonEnClair, user.getId(),
            java.sql.Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES)),
            java.sql.Timestamp.from(Instant.now()));
    }

    private User compte(String prefixe) {
        User user = new User();
        user.setEmail(uniqueEmail("empreinte-" + prefixe));
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
        user.setDisplayName("Compte " + prefixe);
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);
        user.setIsActive(true);
        return userRepository.saveAndFlush(user);
    }
}
