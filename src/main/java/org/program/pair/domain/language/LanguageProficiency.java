package org.program.pair.domain.language;

/**
 * À quel point on parle une langue — déclaré, jamais vérifié.
 *
 * <p>Quatre paliers, et pas de cinquième : le but est de savoir si deux
 * personnes pourront se comprendre sur un terrain de sport, pas de mesurer un
 * niveau. Un palier de plus inviterait à comparer.
 */
public enum LanguageProficiency {
    /** Langue maternelle. */
    NATIVE,
    /** À l'aise en toute situation. */
    FLUENT,
    /** De quoi tenir une conversation. */
    CONVERSATIONAL,
    /** Quelques mots. */
    BASIC
}
