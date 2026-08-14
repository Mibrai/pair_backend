package org.program.pair.domain.chat;

public enum ConversationType {
    DIRECT,
    GROUP,
    /**
     * Fil de diffusion d'un programme : l'auteur écrit, les participants lisent.
     *
     * <p>Un seul par programme, créé à la première diffusion — inutile de peupler
     * la base de fils vides. L'appartenance en est <b>dérivée</b> des inscriptions
     * actives et n'est jamais recopiée : voir {@code ChatService}.
     */
    PROGRAM_BROADCAST
}
