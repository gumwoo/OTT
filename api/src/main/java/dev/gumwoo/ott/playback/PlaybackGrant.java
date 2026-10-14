package dev.gumwoo.ott.playback;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseCookie;

public record PlaybackGrant(UUID sessionId, String manifestUrl, Instant expiresAt, List<ResponseCookie> cookies) {
}
