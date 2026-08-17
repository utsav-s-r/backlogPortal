package com.college.backlog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full context against a LOCAL throwaway Postgres — never prod; the "test" profile in
 * src/test/resources/application-test.properties pins the datasource to localhost. Flyway builds
 * the schema from V1__baseline.sql on the empty DB, then `ddl-auto=validate` asserts it matches
 * the entities, proving a fresh Flyway-built schema and the code agree.
 *
 * Needs a Postgres at the test datasource (CI provides one). Locally:
 *   docker run -d --name backlog-test -e POSTGRES_USER=verify -e POSTGRES_PASSWORD=verify \
 *     -e POSTGRES_DB=backlog -p 5433:5432 postgres:18
 * Not run by targeted unit-test runs.
 */
@SpringBootTest
@ActiveProfiles("test")
class BacklogApplicationTests {

	@Test
	void contextLoads() {
	}

}
