package com.veloce;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class Veloce {
    public static void main(String[] args) {
        SpringApplication.run(Veloce.class, args);
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean(name="calendarScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler calendarScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("veloce-calendar-");return scheduler;
    }

    @Bean(name="taskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("veloce-jobs-");return scheduler;
    }
}
