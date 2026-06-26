package org.program.pair.domain.indexation;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.Program;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * JPA Entity Listener for automatic search indexation
 * Listens to Program entity changes and triggers reindexation
 */
@Component
@Slf4j
public class ProgramIndexationListener {

    private static IndexationService indexationService;

    @Autowired
    @Lazy
    public void setIndexationService(IndexationService indexationService) {
        ProgramIndexationListener.indexationService = indexationService;
    }

    @PostPersist
    public void afterCreate(Program program) {
        log.debug("Program created: {}, triggering indexation", program.getId());
        if (indexationService != null) {
            indexationService.updateProgramSearchVector(program.getId());
        }
    }

    @PostUpdate
    public void afterUpdate(Program program) {
        log.debug("Program updated: {}, triggering indexation", program.getId());
        if (indexationService != null) {
            indexationService.updateProgramSearchVector(program.getId());
        }
    }

    @PostRemove
    public void afterRemove(Program program) {
        log.debug("Program removed: {}", program.getId());
        // Search vector is automatically removed with the program (cascade)
    }
}
