package pl.dolien.climbcheck;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's single Clock — injected wherever "now" matters (LP snapshot
 * staleness, rate limiting). Tests replace it with Clock.fixed(...) for
 * deterministic time-dependent behaviour.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
