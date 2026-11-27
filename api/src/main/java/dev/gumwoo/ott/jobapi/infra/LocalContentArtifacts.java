package dev.gumwoo.ott.jobapi.infra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import dev.gumwoo.ott.jobapi.ContentArtifacts;

// transcoder 로컬 모드와 같은 디렉터리를 본다 (app-design.md §10 저장소 인터페이스)
public class LocalContentArtifacts implements ContentArtifacts {

	private final Path root;

	public LocalContentArtifacts(Path root) {
		this.root = root;
	}

	@Override
	public List<String> problems(String contentId, int version) {
		Path dir = this.root.resolve(contentId).resolve("v" + version);
		return HlsVersionCheck.problems(new HlsVersionCheck.Files() {

			@Override
			public Optional<List<String>> lines(String name) {
				Path path = dir.resolve(name);
				return Files.isRegularFile(path) ? Optional.of(read(path)) : Optional.empty();
			}

			@Override
			public boolean exists(String name) {
				return Files.isRegularFile(dir.resolve(name));
			}

		});
	}

	@Override
	public boolean delete(String contentId, int version) {
		Path root = this.root.toAbsolutePath().normalize();
		Path dir = root.resolve(contentId).resolve("v" + version).normalize();
		if (!dir.startsWith(root) || dir.equals(root) || !Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return true;
	}

	private static List<String> read(Path path) {
		try {
			return HlsVersionCheck.lines(Files.readString(path, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
