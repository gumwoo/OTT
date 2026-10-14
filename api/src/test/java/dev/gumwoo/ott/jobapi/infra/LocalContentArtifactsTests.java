package dev.gumwoo.ott.jobapi.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LocalContentArtifactsTests {

	@TempDir
	Path root;

	@Test
	void completeVersionHasNoProblems() throws IOException {
		Path dir = version("movie", 1);
		master(dir, "360p.m3u8");
		media(dir, "360p.m3u8", "seg0.ts", true);
		Files.write(dir.resolve("seg0.ts"), new byte[] { 1 });

		assertThat(new LocalContentArtifacts(this.root).problems("movie", 1)).isEmpty();
	}

	@Test
	void missingMasterIsReported() {
		assertThat(new LocalContentArtifacts(this.root).problems("movie", 9)).containsExactly("missing master.m3u8");
	}

	@Test
	void unfinishedPlaylistAndMissingSegmentAreReported() throws IOException {
		Path dir = version("movie", 1);
		master(dir, "360p.m3u8");
		media(dir, "360p.m3u8", "seg0.ts", false);

		assertThat(new LocalContentArtifacts(this.root).problems("movie", 1))
			.containsExactlyInAnyOrder("360p.m3u8: missing #EXT-X-ENDLIST", "missing seg0.ts");
	}

	@Test
	void pathsOutsideTheVersionAreRejected() throws IOException {
		Path dir = version("movie", 2);
		Path other = version("movie", 1);
		media(other, "360p.m3u8", "seg0.ts", true);
		Files.write(other.resolve("seg0.ts"), new byte[] { 1 });
		master(dir, "../v1/360p.m3u8");

		assertThat(new LocalContentArtifacts(this.root).problems("movie", 2))
			.containsExactly("variant must be a sibling file: ../v1/360p.m3u8");
	}

	private Path version(String contentId, int version) throws IOException {
		return Files.createDirectories(this.root.resolve(contentId).resolve("v" + version));
	}

	private static void master(Path dir, String variant) throws IOException {
		Files.writeString(dir.resolve("master.m3u8"), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n" + variant + "\n");
	}

	private static void media(Path dir, String name, String segment, boolean ended) throws IOException {
		Files.writeString(dir.resolve(name),
				"#EXTM3U\n#EXTINF:6.0,\n" + segment + "\n" + (ended ? "#EXT-X-ENDLIST\n" : ""));
	}

}
