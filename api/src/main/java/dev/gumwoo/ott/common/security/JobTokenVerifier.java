package dev.gumwoo.ott.common.security;

// 운영은 ServiceAccount 토큰 + TokenReview, local·test는 고정 개발용 토큰 (app-design.md §6)
public interface JobTokenVerifier {

	boolean verify(String token);

}
