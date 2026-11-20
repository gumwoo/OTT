package dev.gumwoo.ott.jobapi;

import dev.gumwoo.ott.TestcontainersConfiguration;
import dev.gumwoo.ott.common.security.JobTokenVerifier;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

// 운영 CronJob은 웹 서버 없이 뜬다. Job 토큰 설정이 없는 Pod에서도 기동해야 한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ott.ops-check.run=true")
@Import(TestcontainersConfiguration.class)
class OpsCheckModeTests {

	@Autowired
	ApplicationContext context;

	@Test
	void runsWithoutWebSecurityAndExitsCleanly() {
		assertThat(this.context.getBeansOfType(OpsCheck.class)).hasSize(1);
		assertThat(this.context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
		assertThat(this.context.getBeansOfType(JobTokenVerifier.class)).isEmpty();
		assertThat(this.context.getBean(OpsCheck.class).getExitCode()).isZero();
	}

}
