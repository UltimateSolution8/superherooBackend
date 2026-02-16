package com.helpinminutes.api.bootstrap;

import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;

  public BootstrapAdminRunner(UserRepository users, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(ApplicationArguments args) {
    String phone = System.getenv("BOOTSTRAP_ADMIN_PHONE");
    if (phone == null || phone.isBlank()) {
      return;
    }

    String email = System.getenv("BOOTSTRAP_ADMIN_EMAIL");
    String password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");

    users.findByPhone(phone).ifPresentOrElse(existing -> {
      if (existing.getRole() != UserRole.ADMIN) {
        log.warn("BOOTSTRAP_ADMIN_PHONE user exists but is not ADMIN (phone={})", phone);
        return;
      }
      boolean updated = false;
      if (email != null && !email.isBlank() && (existing.getEmail() == null || existing.getEmail().isBlank())) {
        existing.setEmail(email.trim().toLowerCase());
        updated = true;
      }
      if (password != null && !password.isBlank() && (existing.getPasswordHash() == null || existing.getPasswordHash().isBlank())) {
        existing.setPasswordHash(passwordEncoder.encode(password));
        updated = true;
      }
      if (existing.getDisplayName() == null || existing.getDisplayName().isBlank()) {
        existing.setDisplayName("Platform Admin");
        updated = true;
      }
      if (updated) {
        users.save(existing);
        log.info("Updated bootstrapped admin credentials for phone={}", phone);
      }
    }, () -> {
      UserEntity admin = new UserEntity();
      admin.setPhone(phone);
      admin.setRole(UserRole.ADMIN);
      admin.setStatus(UserStatus.ACTIVE);
      admin.setDisplayName("Platform Admin");
      if (email != null && !email.isBlank()) {
        admin.setEmail(email.trim().toLowerCase());
      }
      if (password != null && !password.isBlank()) {
        admin.setPasswordHash(passwordEncoder.encode(password));
      }
      users.save(admin);
      log.info("Bootstrapped admin user for phone={}", phone);
    });
  }
}
