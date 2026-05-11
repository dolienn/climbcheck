package pl.dolien.climbcheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClimbcheckApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClimbcheckApplication.class, args);
	}

}
