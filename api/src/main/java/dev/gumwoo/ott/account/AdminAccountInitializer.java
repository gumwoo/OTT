package dev.gumwoo.ott.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class AdminAccountInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

	private final AdminAccountProperties properties;

	private final AccountService accounts;

	AdminAccountInitializer(AdminAccountProperties properties, AccountService accounts) {
		this.properties = properties;
		this.accounts = accounts;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!this.properties.configured()) {
			return;
		}
		boolean created = this.accounts.createAdminIfAbsent(this.properties.email(), this.properties.password());
		log.info(created ? "Admin account created" : "Admin account already exists");
	}

}
