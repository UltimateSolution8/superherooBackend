package com.helpinminutes.api.bootstrap;

import com.helpinminutes.api.users.model.UserEntity;
import com.helpinminutes.api.users.model.UserRole;
import com.helpinminutes.api.users.model.UserStatus;
import com.helpinminutes.api.users.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final UserRepository users;

  public BootstrapAdminRunner(UserRepository users) {
    this.users = users;
  }

  @Override
  public void run(ApplicationArguments args) {
    String phone = System.getenv("BOOTSTRAP_ADMIN_PHONE");
    if (phone == null || phone.isBlank()) {
      return;
    }

    users.findByPhone(phone).ifPresentOrElse(existing -> {
      if (existing.getRole() != UserRole.ADMIN) {
        log.warn("BOOTSTRAP_ADMIN_PHONE user exists but is not ADMIN (phone={})", phone);
      }
    }, () -> {
      UserEntity admin = new UserEntity();
      admin.setPhone(phone);
      admin.setRole(UserRole.ADMIN);
      admin.setStatus(UserStatus.ACTIVE);
      admin.setDisplayName("Admin");
      users.save(admin);
      log.info("Bootstrapped admin user for phone={}", phone);
    });
  }
}
