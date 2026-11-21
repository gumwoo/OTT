package dev.gumwoo.ott;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(ApiApplication.class, args);
		// 운영 CronJob은 한 번 점검하고 결과를 종료 코드로 남긴다 (jobapi.OpsCheck)
		if (context.getEnvironment().getProperty("ott.ops-check.run", Boolean.class, false)) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
