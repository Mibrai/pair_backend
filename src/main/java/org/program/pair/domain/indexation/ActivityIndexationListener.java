package org.program.pair.domain.indexation;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.activity.Activity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * JPA Entity Listener for Activity search indexation
 */
@Component
@Slf4j
public class ActivityIndexationListener {

    private static IndexationService indexationService;

    @Autowired
    @Lazy
    public void setIndexationService(IndexationService indexationService) {
        ActivityIndexationListener.indexationService = indexationService;
    }

    @PostPersist
    public void afterCreate(Activity activity) {
        log.debug("Activity created: {}, triggering indexation", activity.getId());
        if (indexationService != null) {
            indexationService.updateActivitySearchVector(activity.getId());
        }
    }

    @PostUpdate
    public void afterUpdate(Activity activity) {
        log.debug("Activity updated: {}, triggering indexation", activity.getId());
        if (indexationService != null) {
            indexationService.updateActivitySearchVector(activity.getId());
        }
    }
}
