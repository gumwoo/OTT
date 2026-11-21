package dev.gumwoo.ott.common.security;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

// credentials: TokenReview를 호출하는 api 자신의 ServiceAccount 토큰. kubelet이 갱신하므로 호출마다 읽는다
@ConfigurationProperties("ott.internal.token-review")
record TokenReviewProperties(boolean enabled, @DefaultValue("https://kubernetes.default.svc") URI apiServer,
		@DefaultValue("file:/var/run/secrets/kubernetes.io/serviceaccount/token") Resource credentials,
		@DefaultValue("kubernetes") String sslBundle, @DefaultValue("ott-api") String audience, String serviceAccount) {

}
