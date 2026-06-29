package com.eventmanager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean(name = "cassandraExecutor")
    public Executor cassandraExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "kafkaExecutor")
    public Executor kafkaExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
