package dev.gumwoo.ott.jobapi;

import java.nio.file.Path;

import dev.gumwoo.ott.jobapi.infra.LocalContentArtifacts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class JobApiConfig {

	// S3 구현은 AWS 단계에서 붙인다
	@Bean
	ContentArtifacts contentArtifacts(@Value("${ott.content.local-root:#{null}}") String localRoot) {
		if (localRoot == null) {
			throw new IllegalStateException("ott.content.local-root is required until the S3 adapter exists");
		}
		return new LocalContentArtifacts(Path.of(localRoot));
	}

}
