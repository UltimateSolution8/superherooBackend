package com.helpinminutes.api.bootstrap;

import com.helpinminutes.api.common.InputValidators;
import com.helpinminutes.api.common.ServiceArea;
import com.helpinminutes.api.helpers.model.HelperKycStatus;
import com.helpinminutes.api.helpers.model.HelperProfileEntity;
import com.helpinminutes.api.helpers.repo.HelperProfileRepository;
import com.helpinminutes.api.mediator.model.HelperMediatorLinkEntity;
import com.helpinminutes.api.mediator.repo.HelperMediatorLinkRepository;
import com.helpinminutes.api.payments.model.PaymentCollectionMode;
import com.helpinminutes.api.tasks.model.TaskEntity;
import com.helpinminutes.api.tasks.model.TaskStatus;
import com.helpinminutes.api.tasks.model.TaskUrgency;
import com.helpinminutes.api.tasks.repo.TaskRepository;
import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the demo accounts Google Play reviewers sign in with.
 *
 * These are deliberately <em>ordinary</em> accounts. The previous mechanism was
 * a hardcoded-phone bypass in AuthService that skipped OTP verification, forced
 * KYC to APPROVED, and waived the geofence and AI-moderation checks — so a
 * reviewer exercised a code path no real user ever hits, and anyone who guessed
 * the phone numbers got a free account with elevated privileges.
 *
 * Here the accounts are seeded through the same fields a real signup writes, and
 * from then on they authenticate exactly like everybody else. The only special
 * treatment is that they already exist, are email-verified, have approved KYC,
 * and carry enough history that the app does not look empty on first launch.
 *
 * Disabled unless {@code REVIEWER_SEED_ENABLED=true}. Give each account a strong,
 * unique password via the {@code REVIEWER_*_PASSWORD} vars and rotate them after
 * the review completes.
 */
@Component
@Order(20) // after BootstrapAdminRunner
public class ReviewerAccountRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ReviewerAccountRunner.class);

  /** Hyderabad addresses inside the 55km service radius, so bookings match normally. */
  private static final double HOME_LAT = 17.4401;
  private static final double HOME_LNG = 78.3489;
  private static final String HOME_ADDRESS = "Hitech City Main Road, Madhapur, Hyderabad, Telangana 500081";

  private static final double WORK_LAT = 17.4239;
  private static final double WORK_LNG = 78.4738;
  private static final String WORK_ADDRESS = "Banjara Hills Road No. 12, Hyderabad, Telangana 500034";

  private final UserRepository users;
  private final HelperProfileRepository helperProfiles;
  private final HelperMediatorLinkRepository helperMediatorLinks;
  private final TaskRepository tasks;
  private final PasswordEncoder passwordEncoder;

  public ReviewerAccountRunner(
      UserRepository users,
      HelperProfileRepository helperProfiles,
      HelperMediatorLinkRepository helperMediatorLinks,
      TaskRepository tasks,
      PasswordEncoder passwordEncoder) {
    this.users = users;
    this.helperProfiles = helperProfiles;
    this.helperMediatorLinks = helperMediatorLinks;
    this.tasks = tasks;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!"true".equalsIgnoreCase(System.getenv("REVIEWER_SEED_ENABLED"))) {
      return;
    }
    try {
      seed();
    } catch (Exception e) {
      // Never let demo seeding take the service down.
      log.error("Reviewer account seeding failed: {}", e.getMessage(), e);
    }
  }

  private void seed() {
    ReviewerSpec citizen = specFrom("CITIZEN", UserRole.BUYER, "Arjun Rao (Demo)");
    ReviewerSpec partner = specFrom("PARTNER", UserRole.HELPER, "Vikram Singh (Demo)");
    ReviewerSpec mediator = specFrom("MEDIATOR", UserRole.MEDIATOR, "Priya Nair (Demo)");

    if (citizen == null && partner == null && mediator == null) {
      log.warn("REVIEWER_SEED_ENABLED is true but no REVIEWER_*_EMAIL/PASSWORD pairs are set");
      return;
    }

    UserEntity citizenUser = citizen == null ? null : upsert(citizen);
    UserEntity partnerUser = partner == null ? null : upsert(partner);
    UserEntity mediatorUser = mediator == null ? null : upsert(mediator);

    if (partnerUser != null) {
      approveKyc(partnerUser, partner.displayName());
    }
    if (partnerUser != null && mediatorUser != null) {
      linkHelperToMediator(partnerUser.getId(), mediatorUser.getId());
    }
    if (citizenUser != null) {
      seedTaskHistory(citizenUser, partnerUser);
    }

    log.info("Reviewer accounts ready (citizen={}, partner={}, mediator={})",
        citizenUser != null, partnerUser != null, mediatorUser != null);
  }

  /** Reads REVIEWER_&lt;KEY&gt;_EMAIL / _PASSWORD / _PHONE; null when unconfigured. */
  private ReviewerSpec specFrom(String key, UserRole role, String defaultName) {
    String email = env("REVIEWER_" + key + "_EMAIL");
    String password = env("REVIEWER_" + key + "_PASSWORD");
    if (email == null || password == null) return null;
    String phone = env("REVIEWER_" + key + "_PHONE");
    String name = Optional.ofNullable(env("REVIEWER_" + key + "_NAME")).orElse(defaultName);
    return new ReviewerSpec(InputValidators.requireEmail(email), password, phone, name, role);
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * Creates or refreshes the account. The password is always reset so a rotated
   * env var takes effect on the next deploy, which matters when the review
   * credentials have to be reissued.
   */
  private UserEntity upsert(ReviewerSpec spec) {
    UserEntity user = users.findByEmail(spec.email()).orElseGet(UserEntity::new);
    user.setEmail(spec.email());
    user.setRole(spec.role());
    user.setStatus(UserStatus.ACTIVE);
    user.setDisplayName(spec.displayName());
    user.setPasswordHash(passwordEncoder.encode(spec.password()));
    // Pre-verified so a reviewer is never blocked waiting on an inbox they
    // cannot reach. Booking is gated on this flag.
    user.setEmailVerified(true);
    if (spec.phone() != null) {
      user.setPhone(InputValidators.normalizeIndianPhoneOrNull(spec.phone()));
    }
    return users.save(user);
  }

  private void approveKyc(UserEntity partner, String displayName) {
    HelperProfileEntity profile = helperProfiles.findById(partner.getId())
        .orElseGet(() -> {
          HelperProfileEntity created = new HelperProfileEntity();
          created.setUserId(partner.getId());
          return created;
        });
    profile.setKycStatus(HelperKycStatus.APPROVED);
    profile.setKycFullName(displayName);
    if (profile.getRating() == null) {
      profile.setRating(new BigDecimal("4.8"));
    }
    helperProfiles.save(profile);
  }

  private void linkHelperToMediator(java.util.UUID helperId, java.util.UUID mediatorId) {
    HelperMediatorLinkEntity link = helperMediatorLinks
        .findByHelperIdAndMediatorId(helperId, mediatorId)
        .orElseGet(HelperMediatorLinkEntity::new);
    link.setHelperId(helperId);
    link.setMediatorId(mediatorId);
    link.setStatus("ACTIVE");
    link.setCreatedBy("HELPER");
    helperMediatorLinks.save(link);
  }

  /**
   * Gives the citizen one completed, rated job so History, Ratings and the
   * partner's Earnings screens have real content instead of empty states.
   *
   * Seeded once — re-running must not pile up duplicates on every deploy.
   */
  private void seedTaskHistory(UserEntity citizen, UserEntity partner) {
    if (partner == null) return;
    if (tasks.countByBuyerIdAndStatus(citizen.getId(), TaskStatus.COMPLETED) > 0) return;

    Instant completedAt = Instant.now().minus(3, ChronoUnit.DAYS);

    TaskEntity task = new TaskEntity();
    task.setBuyerId(citizen.getId());
    task.setAssignedHelperId(partner.getId());
    task.setTitle("Help moving a two-seater sofa");
    task.setDescription("Needed a hand shifting a sofa from the living room to the guest bedroom.");
    task.setUrgency(TaskUrgency.NORMAL);
    task.setTimeMinutes(45);
    task.setBudgetPaise(45_000L); // ₹450
    task.setLat(HOME_LAT);
    task.setLng(HOME_LNG);
    task.setAddressText(HOME_ADDRESS);
    task.setLandmark("Near Cyber Towers");
    task.setPaymentCollectionMode(PaymentCollectionMode.PAY_AFTER_SERVICE);
    task.setStatus(TaskStatus.COMPLETED);
    task.setWorkStartedAt(completedAt.minus(50, ChronoUnit.MINUTES));
    task.setBuyerRating(new BigDecimal("5.0"));
    task.setBuyerRatingComment("On time and very careful with the furniture.");
    task.setBuyerRatedAt(completedAt);
    task.setHelperRating(new BigDecimal("5.0"));
    task.setHelperRatingComment("Clear directions, easy job.");
    task.setHelperRatedAt(completedAt);
    // createdAt/updatedAt are set by JPA lifecycle callbacks, so the seeded job
    // shows as created now rather than three days ago. Harmless for a demo.
    tasks.save(task);

    // Guard against seeding somewhere unbookable if the coordinates are ever edited.
    if (!ServiceArea.isWithinHyderabad(HOME_LAT, HOME_LNG)
        || !ServiceArea.isWithinHyderabad(WORK_LAT, WORK_LNG)) {
      log.warn("Reviewer demo coordinates fall outside the Hyderabad service area");
    }

    log.info("Seeded reviewer task history");
  }

  private record ReviewerSpec(
      String email, String password, String phone, String displayName, UserRole role) {}
}
