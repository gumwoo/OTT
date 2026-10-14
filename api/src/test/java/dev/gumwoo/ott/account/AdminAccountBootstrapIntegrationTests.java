package dev.gumwoo.ott.account;

import dev.gumwoo.ott.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "ott.admin.email=Ops@Example.com", "ott.admin.password=bootstrap-pass-1" })
@Import(TestcontainersConfiguration.class)
class AdminAccountBootstrapIntegrationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void adminIsCreatedFromPropertiesOnStartup() {
		assertThat(this.jdbc.queryForObject("select role from users where email = 'ops@example.com'", String.class))
			.isEqualTo("ADMIN");
	}

}
