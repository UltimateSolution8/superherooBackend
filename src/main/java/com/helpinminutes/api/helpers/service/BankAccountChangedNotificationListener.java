package com.helpinminutes.api.helpers.service;

import com.helpinminutes.api.notifications.service.PushNotificationService;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BankAccountChangedNotificationListener {
  private final PushNotificationService push;

  public BankAccountChangedNotificationListener(PushNotificationService push) { this.push = push; }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async("adminNotificationExecutor")
  public void onChanged(BankAccountChangedEvent event) {
    push.notifyBankAccountChanged(event.userId(), event.bankName(), event.last4());
  }
}
