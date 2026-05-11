package pl.dolien.climbcheck.riot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RiotConfig {

    @Bean
    RestClient restClient() {
        return RestClient.builder().build();
    }
}
