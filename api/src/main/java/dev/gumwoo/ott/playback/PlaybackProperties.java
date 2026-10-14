package dev.gumwoo.ott.playback;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

// resourceBaseUrl: CloudFront 도메인 (예: https://dxxxx.cloudfront.net)
@ConfigurationProperties("ott.playback")
record PlaybackProperties(@DefaultValue("30m") Duration ttl, String resourceBaseUrl, Signing signing) {

	record Signing(String keyPairId, Resource privateKey, boolean ephemeralKey) {
	}

}
