package com.helpinminutes.api.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OtpDeliveryExecutorConfig {

  /**
   * Core == max: the queue fills before a ThreadPoolExecutor grows past core, so
   * core 2 / max 4 / queue 500 never used more than two threads.
   *
   * <p>Shutdown now drains. Previously {@code waitForTasksToCompleteOnShutdown}
   * was false, so every deploy silently discarded queued OTP sends — a user who
   * requested a code a second before a restart never received it and had no way
   * to tell why.
   */
  @Bean(name = "otpDeliveryExecutor")
  public Executor otpDeliveryExecutor(
      @Value("${OTP_DELIVERY_POOL_SIZE:3}") int poolSize,
      @Value("${OTP_DELIVERY_QUEUE_CAPACITY:100}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(poolSize);
    executor.setMaxPoolSize(poolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("otp-delivery-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    executor.initialize();
    return executor;
  }
}
