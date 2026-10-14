package dev.gumwoo.ott.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 비밀번호를 저장소에 남기지 않으려고 실행 환경변수로 받는다 (app-design.md §5)
@ConfigurationProperties("ott.admin")
record AdminAccountProperties(String email, String password) {

	boolean configured() {
		return this.email != null && !this.email.isBlank() && this.password != null && !this.password.isBlank();
	}

}
