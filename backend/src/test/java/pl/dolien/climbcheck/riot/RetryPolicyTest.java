package pl.dolien.climbcheck.riot;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void delayForAttempt_shouldGrowExponentially() {
        RetryPolicy policy = new RetryPolicy(5, 1000, 60000);

        assertThat(policy.delayForAttempt(1, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayForAttempt(2, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForAttempt(3, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayForAttempt(4, null)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void delayForAttempt_shouldCapAtMaxDelay() {
        RetryPolicy policy = new RetryPolicy(10, 1000, 5000);

        assertThat(policy.delayForAttempt(4, null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayForAttempt(10, null)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void delayForAttempt_shouldRespectRetryAfterWhenLongerThanBackoff() {
        RetryPolicy policy = new RetryPolicy(5, 1000, 60000);

        assertThat(policy.delayForAttempt(1, Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void delayForAttempt_shouldUseBackoffWhenRetryAfterShorter() {
        RetryPolicy policy = new RetryPolicy(5, 1000, 60000);

        assertThat(policy.delayForAttempt(2, Duration.ofMillis(100)))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void delayForAttempt_shouldCapRetryAfterAtMaxDelay() {
        RetryPolicy policy = new RetryPolicy(5, 1000, 5000);

        assertThat(policy.delayForAttempt(1, Duration.ofHours(1)))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void maxAttempts_shouldReturnConfiguredValue() {
        RetryPolicy policy = new RetryPolicy(4, 1000, 60000);

        assertThat(policy.maxAttempts()).isEqualTo(4);
    }
}
