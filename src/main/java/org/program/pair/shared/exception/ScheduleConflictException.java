package org.program.pair.shared.exception;

import org.program.pair.domain.program.dto.ScheduleConflictDto;

import java.util.List;

/**
 * Refus d'inscription pour chevauchement d'agenda.
 *
 * <p>Distincte des autres refus métier par sa charge : le client n'a pas
 * seulement besoin de savoir que c'est refusé, mais de <b>quoi</b> le refuse,
 * pour proposer d'en sortir. {@link GlobalExceptionHandler} lui réserve donc une
 * enveloppe {@code 409} enrichie d'un tableau {@code conflicts}, là où les autres
 * refus se contentent de {@code code} + {@code message}.
 */
public class ScheduleConflictException extends RuntimeException implements HasErrorCode {

    private final List<ScheduleConflictDto> conflicts;

    public ScheduleConflictException(String message, List<ScheduleConflictDto> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<ScheduleConflictDto> getConflicts() {
        return conflicts;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.SCHEDULE_CONFLICT;
    }
}
