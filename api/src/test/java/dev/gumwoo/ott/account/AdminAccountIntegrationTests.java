package dev.gumwoo.ott.account;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import dev.gumwoo.ott.support.Browser;
import dev.gumwoo.ott.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AdminAccountIntegrationTests extends IntegrationTest {

	@Autowired
	AccountService accounts;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void concurrentInitializationCreatesOneAdmin() {
		String email = uniqueEmail();
		CyclicBarrier start = new CyclicBarrier(2);
		Runnable create = () -> {
			await(start);
			this.accounts.createAdminIfAbsent(email, PASSWORD);
		};

		CompletableFuture.allOf(CompletableFuture.runAsync(create), CompletableFuture.runAsync(create)).join();

		assertThat(this.jdbc.queryForObject("select count(*) from users where email = ? and role = 'ADMIN'",
				Integer.class, email))
			.isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"select count(*) from subscriptions s join users u on u.id = s.user_id where u.email = ?",
				Integer.class, email))
			.isEqualTo(1);
	}

	@Test
	void adminEmailTakenByRegularUserFailsStartup() {
		String email = uniqueEmail();
		signedUp(email);

		assertThatIllegalStateException().isThrownBy(() -> this.accounts.createAdminIfAbsent(email, PASSWORD));
	}

	@Test
	void onlyAdminReachesAdminPaths() {
		String adminEmail = uniqueEmail();
		this.accounts.createAdminIfAbsent(adminEmail, PASSWORD);
		Browser admin = browser();
		admin.post("/api/auth/login", credentials(adminEmail, PASSWORD));
		Browser user = loggedIn(uniqueEmail());

		Browser.Response asUser = user.get("/api/admin/nothing");

		assertThat(asUser.status()).isEqualTo(403);
		assertThat(asUser.json().get("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(admin.get("/api/admin/nothing").status()).isEqualTo(404);
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
