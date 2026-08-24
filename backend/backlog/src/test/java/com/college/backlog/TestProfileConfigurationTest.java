package com.college.backlog;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two claims that {@code application-test.properties} makes in comments, turned into checks.
 *
 * <p>Both assert the RESOLVED value, not the file's text: the point is that nothing later in the
 * property chain overrides them. {@code application.properties} imports {@code .env} (line 1,
 * {@code spring.config.import}), and {@code .env} carries the live Neon {@code DB_URL} — so "the
 * literal wins" is a real claim about precedence, verified by hand on 2026-08-17 and only a comment
 * until now. It matters because a test run that silently reached Neon would run every Flyway
 * migration against production.
 *
 * <p>Each is asserted twice, at the property AND at the bean it configures. A property assertion
 * alone would pass if the property were read but never applied.
 */
@SpringBootTest
@ActiveProfiles("test")
class TestProfileConfigurationTest {

    private static final String LOCAL_TEST_DB = "jdbc:postgresql://localhost:5433/backlog";

    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;
    @Autowired private ApplicationContext context;

    /** The .env import must not redirect the test datasource; a pass here is what makes
     *  "./mvnw test is safe" a checked statement rather than a convention. */
    @Test
    void theTestProfileDatasourceIsTheLocalThrowawayNeverNeon() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .as("resolved spring.datasource.url under the test profile")
                .isEqualTo(LOCAL_TEST_DB);

        // The bean is what actually connects — the property could be right and unapplied.
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl())
                .as("the DataSource the context actually connects with")
                .isEqualTo(LOCAL_TEST_DB);
    }

    /**
     * OSIV off is load-bearing, not tidiness: with it on, {@code Registration.subjects} — the only
     * lazy association in the model — would resolve during view rendering and hide every missing
     * transaction boundary. See docs/adr/persistence-fetching.md.
     */
    @Test
    void openInViewIsOffAndNoInterceptorIsRegistered() {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class))
                .as("resolved spring.jpa.open-in-view under the test profile")
                .isFalse();

        // Spring registers this interceptor only when OSIV is enabled, so its absence is the
        // mechanism itself rather than a restatement of the flag.
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class))
                .as("OpenEntityManagerInViewInterceptor beans")
                .isEmpty();
    }
}
