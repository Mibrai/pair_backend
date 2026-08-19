package org.program.pair.domain.program;

/**
 * Par quel chemin un programme est né.
 *
 * <p>Un programme issu du chemin court n'a ni description, ni objectifs, ni
 * durée : on ne les lui a jamais demandés. Sans cette distinction, ces champs
 * vides sont indiscernables de ceux qu'un auteur a délibérément laissés vides
 * dans le formulaire complet — et c'est elle qui permettra de proposer
 * « transformer en programme complet » à qui de droit, sans relancer les autres.
 */
public enum ProgramCreatedVia {

    /** Formulaire complet. Valeur par défaut, et celle de tout l'existant. */
    FULL,

    /** Chemin court : une activité, une date, un lieu, et c'est publié. */
    QUICK
}
