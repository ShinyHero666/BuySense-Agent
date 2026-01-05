package com.buysense;

import com.buysense.run.RunExecutionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@SpringBootApplication(scanBasePackages = {"com.buysense", "com.buysense.sar"})
@ConfigurationPropertiesScan
@EnableScheduling
public class BuySenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuySenseApplication.class, args);
    }

    @Bean("collaborationExecutor")
    Executor collaborationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("bounded-collaboration-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean("agentExecutor")
    Executor agentExecutor(RunExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrent());
        executor.setMaxPoolSize(properties.maxConcurrent());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("buysense-run-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
