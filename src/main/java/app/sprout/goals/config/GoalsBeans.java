package app.sprout.goals.config;

import app.sprout.goals.domain.Engine;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
public class GoalsBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs the engine's round on a schedule ({@code sprout.goals.every}). */
    @Component
    static class Rounds {

        private static final Logger log = LoggerFactory.getLogger(Rounds.class);

        private final Engine engine;

        Rounds(Engine engine) {
            this.engine = engine;
        }

        @Scheduled(fixedDelayString = "${sprout.goals.every:30s}", initialDelayString = "${sprout.goals.every:30s}")
        void round() {
            try {
                engine.round();
            } catch (RuntimeException e) {
                log.warn("Goals round didn't finish: {}", e.getMessage());
            }
        }
    }
}
