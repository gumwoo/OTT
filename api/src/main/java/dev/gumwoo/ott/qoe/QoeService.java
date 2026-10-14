package dev.gumwoo.ott.qoe;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import dev.gumwoo.ott.playback.PlaybackService;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// jsonb 캐스팅과 on conflict do nothing 때문에 JPA 대신 SQL로 쓴다
@Service
public class QoeService {

	// 늦은 재전송과 클라이언트 시계 오차를 감안한 허용 범위
	private static final Duration MAX_AGE = Duration.ofDays(7);

	private static final Duration MAX_SKEW = Duration.ofDays(1);

	private final PlaybackService playback;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Clock clock;

	QoeService(PlaybackService playback, JdbcClient jdbc, JsonMapper json, Clock clock) {
		this.playback = playback;
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	// 재전송은 client_event_id 유일성으로 한 번만 저장된다 (app-design.md §4)
	@Transactional
	public void record(long userId, List<QoeReport> reports) {
		Instant now = this.clock.instant();
		if (reports.stream()
			.map(QoeReport::measuredAt)
			.anyMatch((at) -> at.isBefore(now.minus(MAX_AGE)) || at.isAfter(now.plus(MAX_SKEW)))) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "measuredAt이 허용 범위를 벗어났습니다");
		}
		reports.stream()
			.map(QoeReport::sessionId)
			.distinct()
			.forEach((sessionId) -> this.playback.requireOwnSession(userId, sessionId));
		reports.forEach(this::insert);
	}

	private void insert(QoeReport report) {
		this.jdbc.sql("""
				insert into qoe_events (client_event_id, session_id, kind, startup_ms, watched_ms, position_sec,
				    rebuffer_ms, rebuffer_count, level_switches, errors, measured_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
				on conflict (client_event_id) do nothing
				""")
			.params(report.clientEventId(), report.sessionId(), report.kind().name(), report.startupMs(),
					report.watchedMs(), report.positionSec(), report.rebufferMs(), report.rebufferCount(),
					report.levelSwitches(),
					this.json.writeValueAsString(Objects.requireNonNullElse(report.errors(), List.of())),
					Timestamp.from(report.measuredAt()))
			.update();
	}

}
