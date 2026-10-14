package dev.gumwoo.ott.jobapi.infra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.gumwoo.ott.jobapi.ContentArtifacts;

// transcoder 로컬 모드와 같은 디렉터리를 본다 (app-design.md §10 저장소 인터페이스). 검사 항목은 media/validate_hls.py를 따른다
public class LocalContentArtifacts implements ContentArtifacts {

	private static final Pattern SIBLING = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*");

	private final Path root;

	public LocalContentArtifacts(Path root) {
		this.root = root;
	}

	@Override
	public List<String> problems(String contentId, int version) {
		Path dir = this.root.resolve(contentId).resolve("v" + version);
		Path master = dir.resolve("master.m3u8");
		if (!Files.isRegularFile(master)) {
			return List.of("missing master.m3u8");
		}
		List<String> variants = urisAfter(read(master), "#EXT-X-STREAM-INF");
		if (variants.isEmpty()) {
			return List.of("master.m3u8 references no variants");
		}
		List<String> problems = new ArrayList<>();
		for (String variant : variants) {
			if (!SIBLING.matcher(variant).matches()) {
				problems.add("variant must be a sibling file: " + variant);
				continue;
			}
			Path playlist = dir.resolve(variant);
			if (!Files.isRegularFile(playlist)) {
				problems.add("missing " + variant);
				continue;
			}
			List<String> lines = read(playlist);
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
				else if (!Files.isRegularFile(dir.resolve(segment))) {
					problems.add("missing " + segment);
				}
			}
		}
		return problems;
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

	private static List<String> read(Path path) {
		try {
			return Files.readAllLines(path, StandardCharsets.UTF_8).stream().map(String::strip).toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
