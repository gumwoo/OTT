package dev.gumwoo.ott.qoe;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

import dev.gumwoo.ott.playback.PlaybackService;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QoeService {

	private final PlaybackService playback;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	QoeService(PlaybackService playback, JdbcClient jdbc, JsonMapper json) {
		this.playback = playback;
		this.jdbc = jdbc;
		this.json = json;
	}

	// 재전송은 client_event_id 유일성으로 한 번만 저장된다 (app-design.md §4)
	@Transactional
	public void record(long userId, List<QoeReport> reports) {
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
