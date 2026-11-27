package dev.gumwoo.ott.jobapi.infra;

import java.net.URI;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;

// transcoder 테스트와 같은 moto로 S3 API를 흉내 낸다. 버킷 정책·권한은 AWS 단계에서 확인한다
class S3ContentArtifactsTests {

	private static final String BUCKET = "ott-content";

	private static GenericContainer<?> moto;

	private static S3Client s3;

	private S3ContentArtifacts artifacts;

	@BeforeAll
	static void startMoto() {
		moto = new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.2.5")).withExposedPorts(5000)
			.waitingFor(Wait.forHttp("/moto-api/").forStatusCode(200));
		moto.start();
		s3 = S3Client.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.endpointOverride(URI.create("http://" + moto.getHost() + ":" + moto.getMappedPort(5000)))
			.forcePathStyle(true)
			.build();
		s3.createBucket((create) -> create.bucket(BUCKET)
			.createBucketConfiguration((config) -> config.locationConstraint("ap-northeast-2")));
	}

	@AfterAll
	static void stopMoto() {
		s3.close();
		moto.stop();
	}

	@BeforeEach
	void emptyBucket() {
		s3.listObjectsV2Paginator((list) -> list.bucket(BUCKET))
			.contents()
			.stream()
			.map(S3Object::key)
			.forEach((key) -> s3.deleteObject((delete) -> delete.bucket(BUCKET).key(key)));
		this.artifacts = new S3ContentArtifacts(s3, BUCKET);
	}

	@Test
	void completeVersionHasNoProblems() {
		put("content/movie/v1/master.m3u8", master("360p.m3u8"));
		put("content/movie/v1/360p.m3u8", media("seg0.ts", true));
		put("content/movie/v1/seg0.ts", "x");

		assertThat(this.artifacts.problems("movie", 1)).isEmpty();
	}

	@Test
	void missingObjectsAreReported() {
		assertThat(this.artifacts.problems("movie", 9)).containsExactly("missing master.m3u8");

		put("content/movie/v2/master.m3u8", master("360p.m3u8"));
		put("content/movie/v2/360p.m3u8", media("seg0.ts", false));
		// 다른 버전에 있는 segment는 이 버전의 것으로 치지 않는다
		put("content/movie/v1/seg0.ts", "x");

		assertThat(this.artifacts.problems("movie", 2)).containsExactlyInAnyOrder("360p.m3u8: missing #EXT-X-ENDLIST",
				"missing seg0.ts");
	}

	@Test
	void otherContentWithTheSamePrefixIsNotMixedIn() {
		put("content/movie-2/v1/master.m3u8", master("360p.m3u8"));

		assertThat(this.artifacts.problems("movie", 1)).containsExactly("missing master.m3u8");
	}

	@Test
	void deleteRemovesEveryObjectOfThatVersionOnly() {
		// 한 번에 지울 수 있는 1000개를 넘겨서 나눠 지우는지 본다
		IntStream.range(0, 1001).forEach((i) -> put("content/movie/v2/seg" + i + ".ts", "x"));
		put("content/movie/v1/master.m3u8", master("360p.m3u8"));
		put("content/movie/v20/master.m3u8", master("360p.m3u8"));

		assertThat(this.artifacts.delete("movie", 2)).isTrue();

		assertThat(s3.listObjectsV2Paginator((list) -> list.bucket(BUCKET)).contents().stream().map(S3Object::key))
			.containsExactlyInAnyOrder("content/movie/v1/master.m3u8", "content/movie/v20/master.m3u8");
		assertThat(this.artifacts.delete("movie", 2)).isFalse();
	}

	private static void put(String key, String body) {
		s3.putObject((request) -> request.bucket(BUCKET).key(key), RequestBody.fromString(body));
	}

	private static String master(String variant) {
		return "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n" + variant + "\n";
	}

	private static String media(String segment, boolean ended) {
		return "#EXTM3U\n#EXTINF:6.0,\n" + segment + "\n" + (ended ? "#EXT-X-ENDLIST\n" : "");
	}

}
