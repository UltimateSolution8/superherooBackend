package com.helpinminutes.api.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AdminNotificationExecutorConfig {

  /**
   * Single-threaded by design — admin broadcasts are low-volume and ordering is
   * nice to have. The queue moves from 25 to 100 because a bulk mediator dispatch
   * overflowed 25 trivially, and overflow here means a dropped notification.
   * Shutdown drains for the same reason.
   */
  @Bean(name = "adminNotificationExecutor")
  public Executor adminNotificationExecutor(
      @Value("${ADMIN_NOTIFICATION_QUEUE_CAPACITY:100}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("admin-push-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    executor.initialize();
    return executor;
  }
}
