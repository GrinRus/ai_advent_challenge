package com.aiadvent.backend.flow.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FlowWorkerProperties.class)
public class FlowWorkerConfiguration {

  @Bean(name = "flowWorkerExecutor", destroyMethod = "shutdown")
  public ExecutorService flowWorkerExecutor(FlowWorkerProperties properties) {
    int concurrency = properties.getMaxConcurrency();
    ThreadFactory threadFactory =
        new ThreadFactory() {
          private final AtomicInteger index = new AtomicInteger();

          @Override
          public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("flow-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          }
        };

    return Executors.newFixedThreadPool(concurrency, threadFactory);
  }
}
