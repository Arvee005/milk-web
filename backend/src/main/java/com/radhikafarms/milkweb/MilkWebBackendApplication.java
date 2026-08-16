package com.radhikafarms.milkweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MilkWebBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MilkWebBackendApplication.class, args);
	}

}
