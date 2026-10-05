package app.sprout.goals.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.goals} in goals.yml. */
@ConfigurationProperties("sprout.goals")
public record GoalsProperties(Duration every, String serviceKey, String minimum, String maximum, int maxOpen, String sweepAt,
                              Duration retryAfter, String marketdataUrl, String omsUrl, String accountsUrl, String paymentsUrl) {}
