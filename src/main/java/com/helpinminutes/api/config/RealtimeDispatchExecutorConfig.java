package com.helpinminutes.api.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RealtimeDispatchExecutorConfig {

  /**
   * Core == max on purpose. A ThreadPoolExecutor only grows past its core size
   * once the queue is full, so the previous core 2 / max 4 / queue 500 pool was
   * really a 2-thread pool with a 500-deep queue — max was unreachable.
   *
   * <p>This pool also absorbs the fire-and-forget realtime publishes that used to
   * run on the common ForkJoinPool, which has {@code availableProcessors() - 1}
   * threads (one, on 2 vCPU) and is a poor place for blocking HTTP and JDBC.
   */
  @Bean(name = "realtimeDispatchExecutor")
  public Executor realtimeDispatchExecutor(
      @Value("${REALTIME_DISPATCH_POOL_SIZE:4}") int poolSize,
      @Value("${REALTIME_DISPATCH_QUEUE_CAPACITY:200}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(poolSize);
    executor.setMaxPoolSize(poolSize);
    executor.setQueueCapacity(queueCapacity);
    // CallerRunsPolicy applies back-pressure to the submitting thread rather
    // than dropping a dispatch.
    executor.setRejectedExecutionHandler(
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    executor.setThreadNamePrefix("realtime-dispatch-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    executor.initialize();
    return executor;
  }
}
