package sitemonitor2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class Sitemonitor2Application {

	public static void main(String[] args) {
		SpringApplication.run(Sitemonitor2Application.class, args);
	}

}
