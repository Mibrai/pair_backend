package org.program.pair.domain.outbox;

/**
 * À quoi sert un message de l'outbox.
 *
 * <p><b>Pourquoi un usage explicite, plutôt qu'un {@code userId != null}.</b>
 * L'accusé de remise doit décider quoi faire de ce qu'il apprend : mettre à jour
 * une veille, ou l'état de vérification d'un compte. Déduire cette décision de
 * la présence d'une colonne marcherait aujourd'hui et se tromperait au premier
 * troisième usage — un e-mail de rappel de programme porte lui aussi un compte,
 * et n'a rien à voir avec la vérification d'adresse.
 *
 * <p>Le défaut en base est {@link #WATCH_ALERT} : tout ce que l'outbox portait
 * avant ce lot était une alerte de veille.
 */
public enum OutboxPurpose {

    /** Alerte d'une veille — SMS ou e-mail vers un proche, qui n'a pas de compte. */
    WATCH_ALERT,

    /** E-mail de vérification d'adresse, rattaché au compte qu'il vérifie. */
    EMAIL_VERIFICATION
}
