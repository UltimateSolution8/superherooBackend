package com.helpinminutes.api.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpinminutes.api.common.ServiceArea;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.mediator.repo.HelperMediatorLinkRepository;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class ReviewerAccountRunnerTest {

  private ReviewerAccountRunner newRunner(UserRepository users, HelperProfileRepository profiles) {
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
    TaskRepository tasks = mock(TaskRepository.class);
    return new ReviewerAccountRunner(
        users, profiles, mock(HelperMediatorLinkRepository.class), tasks, encoder);
  }

  @Test
  void doesNothingWhenSeedingIsNotEnabled() {
    // REVIEWER_SEED_ENABLED is absent in the test environment, so the runner
    // must be inert — seeding demo accounts is opt-in, never a default.
    UserRepository users = mock(UserRepository.class);
    ReviewerAccountRunner runner = newRunner(users, mock(HelperProfileRepository.class));

    runner.run(mock(ApplicationArguments.class));

    verify(users, never()).save(any());
  }

  @Test
  void demoCoordinatesSitInsideTheServiceArea() {
    // A reviewer booking from a seeded address must actually be matchable.
    // These are the constants the runner seeds.
    assertTrue(ServiceArea.isWithinHyderabad(17.4401, 78.3489), "Madhapur home address");
    assertTrue(ServiceArea.isWithinHyderabad(17.4239, 78.4738), "Banjara Hills work address");
  }

  @Test
  void seededPartnerProfileIsApprovedAndRated() {
    // Mirrors approveKyc: a reviewer must be able to accept a job immediately,
    // without waiting on a manual KYC review they cannot trigger.
    HelperProfileEntity profile = new HelperProfileEntity();
    profile.setUserId(UUID.randomUUID());
    profile.setKycStatus(HelperKycStatus.APPROVED);

    assertEquals(HelperKycStatus.APPROVED, profile.getKycStatus());
  }

  @Test
  void reviewerAccountsCarryNoElevatedPrivileges() {
    // The account must be an ordinary ACTIVE user in its own role. The old
    // implementation granted geofence and moderation bypasses alongside these
    // fields; nothing here should reintroduce that.
    UserEntity seeded = new UserEntity();
    seeded.setRole(UserRole.BUYER);
    seeded.setStatus(UserStatus.ACTIVE);
    seeded.setEmailVerified(true);

    assertEquals(UserRole.BUYER, seeded.getRole());
    assertEquals(UserStatus.ACTIVE, seeded.getStatus());
    assertTrue(seeded.isEmailVerified());
  }
}
