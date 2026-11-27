package dev.gumwoo.ott.jobapi.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

// 검사 항목은 media/validate_hls.py를 따른다. 저장소마다 읽는 방법만 다르다
final class HlsVersionCheck {

	private static final Pattern SIBLING = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*");

	interface Files {

		// 없으면 empty
		Optional<List<String>> lines(String name);

		boolean exists(String name);

	}

	private HlsVersionCheck() {
	}

	static List<String> problems(Files files) {
		Optional<List<String>> master = files.lines("master.m3u8");
		if (master.isEmpty()) {
			return List.of("missing master.m3u8");
		}
		List<String> variants = urisAfter(master.get(), "#EXT-X-STREAM-INF");
		if (variants.isEmpty()) {
			return List.of("master.m3u8 references no variants");
		}
		List<String> problems = new ArrayList<>();
		for (String variant : variants) {
			if (!SIBLING.matcher(variant).matches()) {
				problems.add("variant must be a sibling file: " + variant);
				continue;
			}
			Optional<List<String>> playlist = files.lines(variant);
			if (playlist.isEmpty()) {
				problems.add("missing " + variant);
				continue;
			}
			List<String> lines = playlist.get();
			List<String> segments = urisAfter(lines, "#EXTINF:");
			if (segments.isEmpty()) {
				problems.add(variant + ": no segments");
			}
			if (!lines.contains("#EXT-X-ENDLIST")) {
				problems.add(variant + ": missing #EXT-X-ENDLIST");
			}
			for (String segment : segments) {
				if (!SIBLING.matcher(segment).matches()) {
					problems.add("segment must be a sibling file: " + segment);
				}
				else if (!files.exists(segment)) {
					problems.add("missing " + segment);
				}
			}
		}
		return problems;
	}

	static List<String> lines(String text) {
		return text.lines().map(String::strip).toList();
	}

	private static List<String> urisAfter(List<String> lines, String tag) {
		List<String> uris = new ArrayList<>();
		for (int i = 0; i < lines.size() - 1; i++) {
			if (lines.get(i).startsWith(tag)) {
				uris.add(lines.get(i + 1));
			}
		}
		return uris;
	}

}
