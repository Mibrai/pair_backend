package org.program.pair.domain.program;

/**
 * Nature d'un média de programme. Renommé de {@code MediaType} (P-BA-17) : un
 * autre {@code MediaType} vit dans {@code domain.media}, et springdoc ne garde
 * qu'un schéma par nom simple. Les valeurs JSON sont inchangées.
 */
public enum ProgramMediaType {
    IMAGE,
    VIDEO
}
