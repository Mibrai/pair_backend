package org.program.pair.domain.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * La propriété des fichiers déposés : qui peut rattacher, qui peut lire, qui
 * peut supprimer.
 *
 * <p><b>Les trois questions, et une seule réponse par question.</b>
 * <ul>
 *   <li>{@link #attacher} — « cette URL que le client m'envoie désigne-t-elle un
 *       fichier que <i>lui</i> a déposé chez nous ? » C'est le volet serveur de
 *       P-MS-01 : sans cette vérification, l'application pouvait enregistrer
 *       {@code https://evil.tld/...} comme pièce jointe d'incident, et le jeton
 *       d'authentification partait chez un hôte étranger à la première
 *       tentative d'affichage.</li>
 *   <li>{@link #verifierLectureAutorisee} — « ce fichier se lit-il par tout
 *       compte connecté ? » Non pour une pièce jointe d'incident (P-BS-11).</li>
 *   <li>{@link #supprimerSiAuteur} — « puis-je effacer ces octets ? » Seulement
 *       si une ligne existe et que son déposant est l'appelant.</li>
 * </ul>
 *
 * <p><b>Le silence de la suppression est délibéré.</b>
 * {@code supprimerSiAuteur} ne lève rien : il rend {@code false}. Les trois
 * routes qui l'appellent ont déjà vérifié l'appartenance de la <i>ressource</i>
 * (mon avatar, mon programme) ; ce qu'elles demandent en plus, c'est de ne pas
 * détruire les octets d'un tiers au passage. Un refus bruyant transformerait
 * « retirer l'image de mon programme » en erreur 4xx pour tous les fichiers
 * déposés avant V109, dont aucun n'a de déposant connaissable — alors que le
 * retrait de l'URL, lui, doit réussir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaFileService {

    /**
     * Le préfixe de service des fichiers. Une URL qui ne commence pas par là ne
     * désigne pas un fichier de ce serveur, et n'est donc jamais rattachable.
     */
    public static final String URL_PREFIX = "/api/media/files/";

    private final MediaFileRepository mediaFileRepository;
    private final StorageService storageService;

    /**
     * Rattache une URL déjà téléversée à un usage, après avoir vérifié qu'elle
     * désigne bien un fichier déposé par l'appelant.
     *
     * <p>Trois refus, un seul code — {@code 400 MEDIA_URL_INVALID} :
     * <ol>
     *   <li>l'URL ne commence pas par {@value #URL_PREFIX} (URL externe, ou
     *       chemin fabriqué) ;</li>
     *   <li>aucune ligne de propriété ne porte ce chemin (fichier antérieur à
     *       V109, ou chemin inventé) ;</li>
     *   <li>la ligne existe mais son déposant n'est pas l'appelant.</li>
     * </ol>
     * Un code unique est volontaire : distinguer « n'existe pas » de « n'est pas
     * à vous » dirait à qui essaie des chemins au hasard lesquels ont touché.
     *
     * @param url     l'URL rendue par le chemin d'upload ; {@code null} ou vide
     *                signifie « pas de pièce jointe », et rend {@code null}
     * @param userId  l'appelant, jamais un identifiant de programme ou d'activité
     * @param purpose l'usage réel, connu seulement ici (voir {@link MediaPurpose})
     * @return l'URL normalisée (sans blancs de bord), ou {@code null}
     */
    @Transactional
    public String attacher(String url, UUID userId, MediaPurpose purpose) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String normalisee = url.strip();
        if (!normalisee.startsWith(URL_PREFIX)) {
            throw refus(normalisee, "URL hors du service de fichiers");
        }

        String path = normalisee.substring(URL_PREFIX.length());
        MediaFile fichier = mediaFileRepository.findById(path)
            .orElseThrow(() -> refus(normalisee, "aucune ligne de propriété"));

        if (!fichier.deposePar(userId)) {
            throw refus(normalisee, "déposé par un autre compte");
        }

        // L'usage réel n'était pas connaissable au dépôt : l'application
        // téléverse pièces jointes et photos de souvenir par le chemin
        // générique, sans paramètre « type ». C'est ici qu'il se fixe, et c'est
        // lui que la lecture d'une pièce jointe d'incident consulte ensuite.
        if (purpose != null && purpose != fichier.getPurpose()) {
            fichier.setPurpose(purpose);
            mediaFileRepository.save(fichier);
        }
        return normalisee;
    }

    /**
     * Refuse la lecture d'un fichier dont l'usage la restreint à son déposant.
     *
     * <p>Un seul usage est concerné : {@link MediaPurpose#INCIDENT_ATTACHMENT}.
     * Le refus est un <b>404 et non un 403</b> — une pièce jointe de
     * signalement ne doit pas se révéler à qui devine son chemin, et un 403
     * dirait « elle existe, mais ». Le message et le code sont exactement ceux
     * d'un fichier absent, pour que les deux cas soient indistinguables.
     *
     * <p>Les autres usages ne changent pas : avatars, images de programme et
     * photos de souvenir restent lisibles par tout compte connecté, comme
     * aujourd'hui. Un fichier <b>sans ligne</b> reste lisible lui aussi : la
     * table n'est pas une liste blanche de lecture, seulement un registre de
     * propriété, et en faire une liste blanche rendrait 404 tous les médias
     * déposés avant V109.
     */
    @Transactional(readOnly = true)
    public void verifierLectureAutorisee(String path, UUID userId) {
        Optional<MediaFile> ligne = mediaFileRepository.findById(path);
        if (ligne.isEmpty()) {
            return;
        }
        MediaFile fichier = ligne.get();
        if (fichier.getPurpose() == MediaPurpose.INCIDENT_ATTACHMENT && !fichier.deposePar(userId)) {
            log.info("Lecture refusée d'une pièce jointe d'incident : chemin {}, demandeur {}", path, userId);
            throw new ResourceNotFoundException(
                ErrorCode.MEDIA_FILE_NOT_FOUND, "Fichier introuvable : " + path);
        }
    }

    /**
     * Efface les octets — et la ligne — si et seulement si l'appelant est le
     * déposant.
     *
     * <p>Ordre voulu : le fichier d'abord, la ligne ensuite. L'inverse
     * laisserait, en cas d'échec disque, un fichier que plus aucune ligne ne
     * rattache — donc définitivement inatteignable par toute suppression.
     *
     * @return {@code true} si les octets ont été effacés
     */
    public boolean supprimerSiAuteur(String path, UUID userId) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Optional<MediaFile> ligne = mediaFileRepository.findById(path);
        if (ligne.isEmpty()) {
            // Le défaut fermé de V109 : sans ligne, personne ne supprime.
            log.info("Suppression ignorée, fichier sans ligne de propriété : {}", path);
            return false;
        }
        if (!ligne.get().deposePar(userId)) {
            log.info("Suppression ignorée, {} n'est pas le déposant de {}", userId, path);
            return false;
        }
        try {
            storageService.delete(path);
        } catch (IOException e) {
            log.warn("Le fichier {} n'a pas pu être effacé du stockage : {}", path, e.getMessage());
            return false;
        }
        mediaFileRepository.deleteById(path);
        return true;
    }

    /**
     * Même règle, à partir d'une URL telle qu'elle est rangée en base.
     *
     * <p>Une URL externe (seed, CDN) ne désigne aucun fichier de ce serveur :
     * rien à supprimer, et surtout aucun appel réseau.
     */
    public boolean supprimerUrlSiAuteur(String url, UUID userId) {
        String path = cheminDe(url);
        return path != null && supprimerSiAuteur(path, userId);
    }

    /**
     * Le chemin de stockage derrière une URL locale, ou {@code null} si l'URL
     * n'en est pas une.
     */
    public String cheminDe(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return null;
        }
        String path = url.substring(URL_PREFIX.length());
        return path.isBlank() ? null : path;
    }

    private ValidationException refus(String url, String raison) {
        log.info("URL de média refusée ({}) : {}", raison, url);
        return new ValidationException(ErrorCode.MEDIA_URL_INVALID,
            "Cette image doit d'abord être téléversée depuis cet appareil.");
    }
}
