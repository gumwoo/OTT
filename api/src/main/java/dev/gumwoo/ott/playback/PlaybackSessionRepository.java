package dev.gumwoo.ott.playback;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PlaybackSessionRepository extends JpaRepository<PlaybackSession, UUID> {

	Optional<PlaybackSession> findByIdAndUserId(UUID id, long userId);

}
