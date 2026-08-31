package org.program.pair.shared.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * L'empreinte d'un secret court, sous une clé qui ne vit pas dans la base.
 *
 * <p><b>Le problème que ceci résout, et qu'un hachage lent ne résout pas.</b> Le
 * module de veille manipule deux secrets dont l'espace est petit : le code de
 * retour, cinq caractères sur un alphabet réduit — de l'ordre de 17 millions de
 * combinaisons — et le numéro de téléphone d'un contact ayant refusé d'être
 * sollicité, dont l'espace des mobiles français tient dans quelques centaines de
 * millions. Ni l'un ni l'autre n'est protégé par bcrypt ou argon2 : un hachage
 * lent <i>ralentit</i> l'énumération d'un si petit espace, il ne l'empêche pas.
 * Quelques heures de calcul suffisent, et elles suffisent une seule fois pour
 * toute la base.
 *
 * <p>La différence apportée ici est de nature et non de degré : la clé de
 * {@code HMAC-SHA256} vit <b>hors de la base</b>, en variable d'environnement.
 * Une fuite de la base seule ne rend alors aucun code et aucun numéro — il
 * faudrait un second compromis, indépendant du premier. C'est cette
 * indépendance qui compte, pas la lenteur.
 *
 * <p><b>Ce que ceci ne protège pas, et qu'il faut dire.</b> Un attaquant qui
 * obtient <i>à la fois</i> la base et la clé énumère les deux espaces en
 * quelques secondes. La défense principale du code de retour reste ailleurs :
 * trois essais, et une durée de vie de quelques heures. Le poivre protège du
 * regard interne et de la fuite de base, ce qui est déjà le scénario le plus
 * probable — il ne protège pas d'un attaquant qui aurait tout, et prétendre le
 * contraire mènerait à relâcher le plafond d'essais, qui est la vraie défense.
 *
 * <p><b>Aucune valeur par défaut, et c'est le point le plus important de cette
 * classe.</b> Une clé de repli inscrite dans le dépôt serait pire que pas de
 * clé du tout : elle donnerait l'apparence d'une configuration correcte, et une
 * base fuitée resterait entièrement énumérable par quiconque a lu le dépôt. Le
 * démarrage échoue donc si la clé manque sous un profil de déploiement. Hors de
 * ces profils, une clé éphémère est tirée au hasard à chaque démarrage, avec un
 * avertissement : les empreintes ne survivent pas au redémarrage, ce qui est
 * exactement ce qu'on veut en développement et jamais ce qu'on veut ailleurs.
 *
 * <p><b>La rotation.</b> Chaque empreinte est écrite à côté de la version de clé
 * qui l'a produite ({@code key_version}), et la vérification relit sous cette
 * version-là. Tourner la clé consiste donc à ajouter une entrée et à avancer
 * {@code pair.pepper.current-version} : les veilles en cours continuent de se
 * lever, et rien n'a besoin d'être réécrit. Une clé retirée trop tôt rend
 * invérifiables les empreintes qu'elle a produites — donc on retire une clé
 * quand plus aucune ligne ne la référence, pas quand on cesse de s'en servir.
 */
@Component
@Slf4j
public class Pepper {

    /** Profils sous lesquels l'absence de clé est une erreur de démarrage. */
    private static final Set<String> PROFILS_DE_DEPLOIEMENT =
        Set.of("prod", "railway", "staging");

    /** Longueur du sel tiré par ligne. 128 bits : deux sels n'entrent jamais en collision. */
    private static final int TAILLE_SEL_OCTETS = 16;

    private static final String ALGORITHME = "HmacSHA256";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Les clés, sous la forme {@code version:base64,version:base64}. Vide par
     * défaut — voir le paragraphe sur l'absence de valeur de repli.
     */
    @Value("${pair.pepper.keys:}")
    private String clesBrutes;

    @Value("${pair.pepper.current-version:1}")
    private int versionCourante;

    private final Environment environment;

    private Map<Integer, byte[]> cles = Map.of();

    public Pepper(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void charger() {
        Map<Integer, byte[]> lues = parser(clesBrutes);

        if (lues.isEmpty()) {
            if (sousDeploiement()) {
                throw new IllegalStateException("""
                    pair.pepper.keys est vide sous un profil de déploiement.

                    Le module de veille refuse de démarrer sans clé : une clé de \
                    repli inscrite dans le dépôt rendrait toute base fuitée \
                    entièrement énumérable, tout en donnant l'apparence d'une \
                    configuration correcte.

                    Posez SAFETY_PEPPER_KEYS au format « 1:<base64 de 32 octets> ».""");
            }
            byte[] ephemere = new byte[32];
            RANDOM.nextBytes(ephemere);
            lues = Map.of(versionCourante, ephemere);
            log.warn("Aucune clé de poivre configurée : une clé éphémère est tirée pour "
                + "ce démarrage. Les empreintes produites ne survivront pas au "
                + "redémarrage. Acceptable en développement, jamais ailleurs.");
        }

        if (!lues.containsKey(versionCourante)) {
            throw new IllegalStateException(
                "pair.pepper.current-version vaut " + versionCourante
                    + " mais aucune clé ne porte cette version. Versions disponibles : "
                    + lues.keySet());
        }

        this.cles = lues;
    }

    /** La version de clé sous laquelle écrire toute empreinte nouvelle. */
    public int versionCourante() {
        return versionCourante;
    }

    /** Un sel neuf, à écrire à côté de l'empreinte qu'il aura servi à produire. */
    public byte[] nouveauSel() {
        byte[] sel = new byte[TAILLE_SEL_OCTETS];
        RANDOM.nextBytes(sel);
        return sel;
    }

    /**
     * L'empreinte d'un secret, sous la version de clé courante.
     *
     * <p>Le sel entre dans le message et non dans la clé : la clé est le poivre,
     * c'est ce qui fait que le sel — qui vit en base à côté de l'empreinte — ne
     * suffit à rien.
     */
    public String empreinte(String clair, byte[] sel) {
        return empreinte(clair, sel, versionCourante);
    }

    public String empreinte(String clair, byte[] sel, int versionDeCle) {
        byte[] cle = cles.get(versionDeCle);
        if (cle == null) {
            throw new IllegalStateException(
                "Aucune clé de poivre en version " + versionDeCle + ". Une clé retirée "
                    + "trop tôt rend invérifiables les empreintes qu'elle a produites : "
                    + "on retire une clé quand plus aucune ligne ne la référence.");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHME);
            mac.init(new SecretKeySpec(cle, ALGORITHME));
            mac.update(sel);
            mac.update(clair.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            // HmacSHA256 est exigé de toute implémentation Java. S'il manque, la
            // JVM est cassée, et se rabattre en silence sur autre chose serait
            // la pire réponse possible.
            throw new IllegalStateException("HmacSHA256 indisponible sur cette JVM", e);
        }
    }

    /**
     * Vrai si le secret présenté produit cette empreinte — <b>en temps
     * constant</b>.
     *
     * <p>{@code MessageDigest.isEqual} et non {@code String.equals} : le second
     * s'arrête au premier octet différent, ce qui laisse mesurer combien de
     * caractères d'un code sont justes, et donc reconstruire le code en le
     * devinant position par position au lieu de l'énumérer.
     *
     * <p><b>Ce que cette méthode ne peut pas garantir seule.</b> Sur le chemin
     * du code de contrainte, la comparaison en temps constant ne suffit pas :
     * l'appelant doit évaluer <i>les deux</i> empreintes — la normale et celle
     * de contrainte — systématiquement, y compris quand la première correspond.
     * Un {@code if} qui court-circuite la seconde rendrait un code normal en un
     * temps et un code de contrainte en un autre, ce qui trahit exactement ce
     * que la fonctionnalité existe pour cacher. C'est peu coûteux ici : un HMAC
     * se calcule en microsecondes, là où un bcrypt en coût 12 aurait rendu cette
     * discipline pratiquement intenable.
     */
    public boolean correspond(String clair, byte[] sel, int versionDeCle, String empreinteAttendue) {
        if (empreinteAttendue == null) {
            // Pas de code de contrainte enregistré. On calcule quand même, pour
            // que le temps de réponse ne distingue pas les comptes qui en ont
            // un de ceux qui n'en ont pas.
            empreinte(clair, sel, versionDeCle);
            return false;
        }
        String obtenue = empreinte(clair, sel, versionDeCle);
        return MessageDigest.isEqual(
            obtenue.getBytes(StandardCharsets.UTF_8),
            empreinteAttendue.getBytes(StandardCharsets.UTF_8));
    }

    private boolean sousDeploiement() {
        for (String profil : environment.getActiveProfiles()) {
            if (PROFILS_DE_DEPLOIEMENT.contains(profil)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, byte[]> parser(String brut) {
        Map<Integer, byte[]> cles = new LinkedHashMap<>();
        if (brut == null || brut.isBlank()) {
            return cles;
        }
        for (String entree : brut.split(",")) {
            String[] parts = entree.trim().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException(
                    "Entrée de pair.pepper.keys mal formée : « " + entree.trim()
                        + " ». Format attendu : « 1:<base64>,2:<base64> ».");
            }
            int version;
            try {
                version = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                    "Version de clé non numérique dans pair.pepper.keys : « " + parts[0] + " ».");
            }
            byte[] cle;
            try {
                cle = Base64.getDecoder().decode(parts[1].trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Clé de poivre en version " + version + " illisible : ce n'est pas du base64.");
            }
            if (cle.length < 32) {
                throw new IllegalStateException(
                    "Clé de poivre en version " + version + " trop courte : " + cle.length
                        + " octets. HMAC-SHA256 demande au moins 32 octets, faute de quoi "
                        + "la clé est plus faible que l'empreinte qu'elle produit.");
            }
            cles.put(version, cle);
        }
        return cles;
    }
}
