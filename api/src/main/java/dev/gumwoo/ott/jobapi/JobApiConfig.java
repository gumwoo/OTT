package dev.gumwoo.ott.jobapi;

import java.net.URI;
import java.nio.file.Path;

import dev.gumwoo.ott.jobapi.infra.LocalContentArtifacts;
import dev.gumwoo.ott.jobapi.infra.S3ContentArtifacts;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class JobApiConfig {

	@Bean
	ContentArtifacts contentArtifacts(@Value("${ott.content.local-root:#{null}}") String localRoot,
			@Value("${ott.content.s3.bucket:#{null}}") String bucket,
			@Value("${ott.content.s3.region:#{null}}") String region,
			@Value("${ott.content.s3.endpoint:#{null}}") String endpoint) {
		// local 프로필 위에 S3를 시험할 때 --ott.content.local-root= 로 끌 수 있게 빈 값은 없는 것으로 본다
		boolean local = StringUtils.hasText(localRoot);
		if (local == StringUtils.hasText(bucket)) {
			throw new IllegalStateException("set exactly one of ott.content.local-root and ott.content.s3.bucket");
		}
		if (local) {
			return new LocalContentArtifacts(Path.of(localRoot));
		}
		S3ClientBuilder builder = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
		if (StringUtils.hasText(region)) {
			builder.region(Region.of(region));
		}
		if (StringUtils.hasText(endpoint)) {
			// 로컬 에뮬레이터는 버킷 이름을 호스트 이름으로 풀지 못한다
			builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
		}
		return new S3ContentArtifacts(builder.build(), bucket);
	}

}
