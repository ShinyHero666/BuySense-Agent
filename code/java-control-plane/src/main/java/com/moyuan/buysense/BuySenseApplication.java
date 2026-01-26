package com.moyuan.buysense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BuySenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuySenseApplication.class, args);
    }

    @Bean("agentExecutor")
    Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("buysense-run-");
        executor.initialize();
        return executor;
    }
}
