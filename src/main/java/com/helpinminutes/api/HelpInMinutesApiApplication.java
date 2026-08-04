package com.helpinminutes.api;

import com.helpinminutes.api.config.AppProperties;
import com.helpinminutes.api.config.ExotelProperties;
import com.helpinminutes.api.config.TwilioProperties;
import com.helpinminutes.api.config.LiveKitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({AppProperties.class, TwilioProperties.class, ExotelProperties.class, LiveKitProperties.class})
public class HelpInMinutesApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(HelpInMinutesApiApplication.class, args);
  }
}
