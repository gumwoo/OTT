package dev.gumwoo.ott.playback;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "playback_sessions")
class PlaybackSession implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(nullable = false)
	private long userId;

	@Column(nullable = false)
	private String contentId;

	// 재생 중 새 버전이 공개돼도 이 버전으로만 갱신한다 (app-design.md §6)
	@Column(nullable = false)
	private int version;

	private String country;

	@Column(nullable = false)
	private Instant issuedAt;

	private Instant lastRefreshedAt;

	@Column(nullable = false)
	private Instant expiresAt;

	@Transient
	private boolean isNew = true;

	protected PlaybackSession() {
	}

	PlaybackSession(long userId, String contentId, int version, String country, Instant issuedAt, Instant expiresAt) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.contentId = contentId;
		this.version = version;
		this.country = country;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	void refreshed(Instant now, Instant expiresAt) {
		this.lastRefreshedAt = now;
		this.expiresAt = expiresAt;
	}

	@Override
	public UUID getId() {
		return this.id;
	}

	@Override
	public boolean isNew() {
		return this.isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	String contentId() {
		return this.contentId;
	}

	int version() {
		return this.version;
	}

	Instant expiresAt() {
		return this.expiresAt;
	}

}
