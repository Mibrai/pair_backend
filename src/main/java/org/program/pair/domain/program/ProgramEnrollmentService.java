package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.dto.*;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing program enrollments and participant activities
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProgramEnrollmentService {

    private final UserProgramRepository userProgramRepository;
    private final ProgramActivityRepository programActivityRepository;
    private final ProgramRepository programRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;

    /**
     * Enroll a user in a program
     *
     * @param userId ID of the user joining
     * @param programId ID of the program to join
     * @param scheduleId Optional: specific schedule to join
     * @return UserProgramDto with enrollment details
     * @throws ResourceNotFoundException if program or schedule not found
     * @throws ValidationException if user already enrolled or program is full
     * @throws ForbiddenException if user tries to join their own program
     */
    public UserProgramDto joinProgram(UUID userId, UUID programId, UUID scheduleId) {
        log.info("User {} attempting to join program {}", userId, programId);

        // Validate user exists
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Validate program exists and is active
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found"));

        if (program.getStatus() != ProgramStatus.ACTIVE) {
            throw new ValidationException(ErrorCode.PROGRAM_NOT_ACTIVE, "Program is not active and cannot accept new participants");
        }

        // Check if user is the program organizer
        if (program.getUserActivity().getUser().getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.PROGRAM_OWN_PROGRAM, "You cannot join your own program");
        }

        // Check if already enrolled
        if (userProgramRepository.existsByUserIdAndProgramIdAndStatusActive(userId, programId)) {
            throw new ValidationException(ErrorCode.PROGRAM_ALREADY_ENROLLED, "You are already enrolled in this program");
        }

        // Validate schedule if provided
        Schedule schedule = null;
        if (scheduleId != null) {
            // Verrou pessimiste : la capacité est partagée avec SlotParticipation
            // (rejoindre un créneau via /api/slots), donc les deux chemins doivent
            // se synchroniser sur la même ligne pour éviter un sur-booking.
            schedule = scheduleRepository.lockById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule not found"));

            if (!schedule.getProgram().getId().equals(programId)) {
                throw new ValidationException(ErrorCode.PROGRAM_SCHEDULE_MISMATCH, "Schedule does not belong to this program");
            }

            // Check schedule capacity (toutes sources de participation confondues)
            if (schedule.getMaxParticipants() != null) {
                long currentParticipants = scheduleRepository
                    .countConfirmedParticipants(scheduleId);

                if (currentParticipants >= schedule.getMaxParticipants()) {
                    throw new ValidationException(ErrorCode.PROGRAM_SCHEDULE_FULL, "This schedule is full");
                }
            }
        }

        // Create enrollment
        UserProgram userProgram = UserProgram.builder()
            .user(user)
            .program(program)
            .schedule(schedule)
            .status(UserProgramStatus.ACTIVE)
            .progressPercentage(0)
            .activitiesCompleted(0)
            .activitiesSkipped(0)
            .build();

        UserProgram saved = userProgramRepository.save(userProgram);
        log.info("User {} successfully joined program {}", userId, programId);

        return toDto(saved);
    }

    /**
     * Unenroll a user from a program with optional reason
     *
     * @param userId ID of the user leaving
     * @param userProgramId ID of the enrollment
     * @param reason Optional reason for leaving
     * @throws ResourceNotFoundException if enrollment not found
     * @throws ForbiddenException if user doesn't own this enrollment
     * @throws ValidationException if already left
     */
    public void leaveProgram(UUID userId, UUID userProgramId, String reason) {
        log.info("User {} attempting to leave user program {}", userId, userProgramId);

        UserProgram userProgram = userProgramRepository.findById(userProgramId)
            .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found"));

        // Validate ownership
        if (!userProgram.getUser().getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.ENROLLMENT_NOT_OWNED, "You can only leave your own enrollments");
        }

        // Check if already left
        if (userProgram.getStatus() == UserProgramStatus.LEFT) {
            throw new ValidationException(ErrorCode.ENROLLMENT_ALREADY_LEFT, "You have already left this program");
        }

        // Update status
        userProgram.setStatus(UserProgramStatus.LEFT);
        userProgram.setLeaveReason(reason);
        userProgram.setLeftAt(Instant.now());

        userProgramRepository.save(userProgram);
        log.info("User {} successfully left program enrollment {}", userId, userProgramId);
    }

    /**
     * Get programs enrolled by a user with optional status filter
     *
     * @param userId ID of the user
     * @param status Optional status filter
     * @return List of user programs
     */
    @Transactional(readOnly = true)
    public List<UserProgramDto> getMyPrograms(UUID userId, UserProgramStatus status) {
        log.debug("Fetching programs for user {} with status {}", userId, status);

        List<UserProgram> userPrograms;
        if (status != null) {
            userPrograms = userProgramRepository.findByUserIdAndStatus(userId, status);
        } else {
            userPrograms = userProgramRepository.findByUserId(userId);
        }

        return userPrograms.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    /**
     * Update progress percentage for a user program
     *
     * @param userId ID of the user
     * @param userProgramId ID of the enrollment
     * @param progressPercentage New progress percentage (0-100)
     * @return Updated UserProgramDto
     * @throws ResourceNotFoundException if enrollment not found
     * @throws ForbiddenException if user doesn't own this enrollment
     * @throws ValidationException if progress is invalid
     */
    public UserProgramDto updateProgress(UUID userId, UUID userProgramId, Integer progressPercentage) {
        log.info("User {} updating progress to {}% for enrollment {}",
                 userId, progressPercentage, userProgramId);

        if (progressPercentage < 0 || progressPercentage > 100) {
            throw new ValidationException(ErrorCode.ENROLLMENT_PROGRESS_OUT_OF_RANGE, "Progress percentage must be between 0 and 100");
        }

        UserProgram userProgram = userProgramRepository.findById(userProgramId)
            .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found"));

        // Validate ownership
        if (!userProgram.getUser().getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.ENROLLMENT_NOT_OWNED, "You can only update your own enrollments");
        }

        // Validate status
        if (userProgram.getStatus() != UserProgramStatus.ACTIVE) {
            throw new ValidationException(ErrorCode.ENROLLMENT_NOT_ACTIVE, "Can only update progress for active enrollments");
        }

        userProgram.setProgressPercentage(progressPercentage);
        userProgram.setLastActivityAt(Instant.now());

        // Auto-complete if 100%
        if (progressPercentage == 100) {
            userProgram.setStatus(UserProgramStatus.COMPLETED);
        }

        UserProgram saved = userProgramRepository.save(userProgram);
        log.info("Progress updated successfully for enrollment {}", userProgramId);

        return toDto(saved);
    }

    /**
     * Mark an activity as completed
     *
     * @param userId ID of the user
     * @param userProgramId ID of the enrollment
     * @param activityId ID of the activity to complete
     * @return Updated UserProgramDto
     * @throws ResourceNotFoundException if enrollment not found
     * @throws ForbiddenException if user doesn't own this enrollment
     */
    public UserProgramDto completeActivity(UUID userId, UUID userProgramId, UUID activityId) {
        log.info("User {} completing activity {} for enrollment {}",
                 userId, activityId, userProgramId);

        UserProgram userProgram = validateUserProgramOwnership(userId, userProgramId);

        // Find or create program activity
        ProgramActivity programActivity = programActivityRepository
            .findByUserProgramIdAndActivityId(userProgramId, activityId)
            .orElseGet(() -> {
                ProgramActivity newActivity = ProgramActivity.builder()
                    .userProgram(userProgram)
                    .activityId(activityId)
                    .status(ProgramActivityStatus.PENDING)
                    .build();
                return programActivityRepository.save(newActivity);
            });

        // Update to completed
        if (programActivity.getStatus() == ProgramActivityStatus.COMPLETED) {
            throw new ValidationException(ErrorCode.ACTIVITY_ALREADY_COMPLETED, "Activity already completed");
        }

        ProgramActivityStatus previousStatus = programActivity.getStatus();
        programActivity.setStatus(ProgramActivityStatus.COMPLETED);
        programActivity.setCompletedAt(Instant.now());
        programActivity.setSkippedAt(null); // Clear skipped if was skipped before
        programActivityRepository.save(programActivity);

        // Update user program stats
        if (previousStatus == ProgramActivityStatus.SKIPPED) {
            userProgram.setActivitiesSkipped(userProgram.getActivitiesSkipped() - 1);
        }
        userProgram.setActivitiesCompleted(userProgram.getActivitiesCompleted() + 1);
        userProgram.setLastActivityAt(Instant.now());

        UserProgram saved = userProgramRepository.save(userProgram);
        log.info("Activity {} completed successfully", activityId);

        return toDto(saved);
    }

    /**
     * Mark an activity as skipped
     *
     * @param userId ID of the user
     * @param userProgramId ID of the enrollment
     * @param activityId ID of the activity to skip
     * @return Updated UserProgramDto
     * @throws ResourceNotFoundException if enrollment not found
     * @throws ForbiddenException if user doesn't own this enrollment
     */
    public UserProgramDto skipActivity(UUID userId, UUID userProgramId, UUID activityId) {
        log.info("User {} skipping activity {} for enrollment {}",
                 userId, activityId, userProgramId);

        UserProgram userProgram = validateUserProgramOwnership(userId, userProgramId);

        // Find or create program activity
        ProgramActivity programActivity = programActivityRepository
            .findByUserProgramIdAndActivityId(userProgramId, activityId)
            .orElseGet(() -> {
                ProgramActivity newActivity = ProgramActivity.builder()
                    .userProgram(userProgram)
                    .activityId(activityId)
                    .status(ProgramActivityStatus.PENDING)
                    .build();
                return programActivityRepository.save(newActivity);
            });

        // Update to skipped
        if (programActivity.getStatus() == ProgramActivityStatus.SKIPPED) {
            throw new ValidationException(ErrorCode.ACTIVITY_ALREADY_SKIPPED, "Activity already skipped");
        }

        ProgramActivityStatus previousStatus = programActivity.getStatus();
        programActivity.setStatus(ProgramActivityStatus.SKIPPED);
        programActivity.setSkippedAt(Instant.now());
        programActivity.setCompletedAt(null); // Clear completed if was completed before
        programActivityRepository.save(programActivity);

        // Update user program stats
        if (previousStatus == ProgramActivityStatus.COMPLETED) {
            userProgram.setActivitiesCompleted(userProgram.getActivitiesCompleted() - 1);
        }
        userProgram.setActivitiesSkipped(userProgram.getActivitiesSkipped() + 1);
        userProgram.setLastActivityAt(Instant.now());

        UserProgram saved = userProgramRepository.save(userProgram);
        log.info("Activity {} skipped successfully", activityId);

        return toDto(saved);
    }

    /**
     * Get participant count for a program
     *
     * @param programId ID of the program
     * @return Number of active participants
     */
    @Transactional(readOnly = true)
    public long getActiveParticipantCount(UUID programId) {
        return userProgramRepository.countActiveParticipantsByProgramId(programId);
    }

    /**
     * Check if a user is enrolled in a program
     *
     * @param userId ID of the user
     * @param programId ID of the program
     * @return true if user is actively enrolled
     */
    @Transactional(readOnly = true)
    public boolean isUserEnrolled(UUID userId, UUID programId) {
        return userProgramRepository.existsByUserIdAndProgramIdAndStatusActive(userId, programId);
    }

    // Private helper methods

    private UserProgram validateUserProgramOwnership(UUID userId, UUID userProgramId) {
        UserProgram userProgram = userProgramRepository.findById(userProgramId)
            .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found"));

        if (!userProgram.getUser().getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.ENROLLMENT_NOT_OWNED, "You can only modify your own enrollments");
        }

        if (userProgram.getStatus() != UserProgramStatus.ACTIVE) {
            throw new ValidationException(ErrorCode.ENROLLMENT_NOT_ACTIVE, "Can only modify active enrollments");
        }

        return userProgram;
    }

    private UserProgramDto toDto(UserProgram userProgram) {
        return new UserProgramDto(
            userProgram.getId(),
            userProgram.getUser().getId(),
            userProgram.getProgram().getId(),
            userProgram.getProgram().getTitle(),
            userProgram.getSchedule() != null ? userProgram.getSchedule().getId() : null,
            userProgram.getStatus(),
            userProgram.getLeaveReason(),
            userProgram.getProgressPercentage(),
            userProgram.getActivitiesCompleted(),
            userProgram.getActivitiesSkipped(),
            userProgram.getLastActivityAt(),
            userProgram.getJoinedAt(),
            userProgram.getLeftAt()
        );
    }
}
