package com.helpinminutes.api;

import com.helpinminutes.api.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class HelpInMinutesApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(HelpInMinutesApiApplication.class, args);
  }
}
