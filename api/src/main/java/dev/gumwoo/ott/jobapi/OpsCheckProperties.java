package dev.gumwoo.ott.jobapi;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// cleanupAfter는 transcode Job의 activeDeadlineSeconds보다 길어야 한다. LOST로 확정된 뒤에도 Pod가 쓰고 있을 수 있다
// (design.md §4 작업 생존 확인)
@ConfigurationProperties("ott.ops-check")
record OpsCheckProperties(boolean run, @DefaultValue("2m") Duration lostAfter,
		@DefaultValue("10m") Duration startDeadline, @DefaultValue("2h") Duration cleanupAfter) {

}
