package dev.gumwoo.ott.playback.web;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.gumwoo.ott.common.security.AuthenticatedUser;
import dev.gumwoo.ott.playback.PlaybackGrant;
import dev.gumwoo.ott.playback.PlaybackService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlaybackController {

	private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

	private final PlaybackService playback;

	PlaybackController(PlaybackService playback) {
		this.playback = playback;
	}

	// 국가는 기록만 한다. 차단은 Edge가 한다 (app-design.md §6)
	@PostMapping("/api/contents/{id}/playback")
	ResponseEntity<PlaybackResponse> start(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
			@RequestHeader(name = "CloudFront-Viewer-Country", required = false) String country) {
		String recorded = (country != null && COUNTRY.matcher(country).matches()) ? country : null;
		return respond(this.playback.start(user.id(), id, recorded));
	}

	@PostMapping("/api/playback/{sessionId}/refresh")
	ResponseEntity<PlaybackResponse> refresh(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID sessionId) {
		return respond(this.playback.refresh(user.id(), sessionId));
	}

	private static ResponseEntity<PlaybackResponse> respond(PlaybackGrant grant) {
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		grant.cookies().forEach((cookie) -> response.header(HttpHeaders.SET_COOKIE, cookie.toString()));
		return response.body(new PlaybackResponse(grant.sessionId(), grant.manifestUrl(), grant.expiresAt()));
	}

	record PlaybackResponse(UUID sessionId, String manifestUrl, Instant expiresAt) {
	}

}
