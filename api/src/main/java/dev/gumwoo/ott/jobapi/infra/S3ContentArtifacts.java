package dev.gumwoo.ott.jobapi.infra;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.gumwoo.ott.jobapi.ContentArtifacts;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

// transcoder S3Storage와 같은 키 배치: content/{id}/v{n}/{name} (design.md §4)
public class S3ContentArtifacts implements ContentArtifacts {

	// DeleteObjects 한 번에 지울 수 있는 최대 개수
	private static final int DELETE_BATCH = 1000;

	private final S3Client s3;

	private final String bucket;

	public S3ContentArtifacts(S3Client s3, String bucket) {
		this.s3 = s3;
		this.bucket = bucket;
	}

	@Override
	public List<String> problems(String contentId, int version) {
		String prefix = prefix(contentId, version);
		// segment마다 HeadObject를 보내지 않고 목록 한 번으로 존재를 확인한다
		Set<String> names = keys(prefix).stream()
			.map((key) -> key.substring(prefix.length()))
			.collect(Collectors.toSet());
		return HlsVersionCheck.problems(new HlsVersionCheck.Files() {

			@Override
			public Optional<List<String>> lines(String name) {
				if (!names.contains(name)) {
					return Optional.empty();
				}
				String text = S3ContentArtifacts.this.s3
					.getObjectAsBytes((get) -> get.bucket(S3ContentArtifacts.this.bucket).key(prefix + name))
					.asString(StandardCharsets.UTF_8);
				return Optional.of(HlsVersionCheck.lines(text));
			}

			@Override
			public boolean exists(String name) {
				return names.contains(name);
			}

		});
	}

	@Override
	public boolean delete(String contentId, int version) {
		List<String> keys = keys(prefix(contentId, version));
		for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
			List<ObjectIdentifier> batch = keys.subList(from, Math.min(from + DELETE_BATCH, keys.size()))
				.stream()
				.map((key) -> ObjectIdentifier.builder().key(key).build())
				.toList();
			DeleteObjectsResponse response = this.s3.deleteObjects(
					(delete) -> delete.bucket(this.bucket).delete(Delete.builder().objects(batch).quiet(true).build()));
			// quiet 모드에서는 실패한 키만 돌아온다
			if (response.hasErrors() && !response.errors().isEmpty()) {
				throw new IllegalStateException("could not delete " + response.errors().size() + " objects under "
						+ prefix(contentId, version) + ": " + response.errors().get(0).message());
			}
		}
		return !keys.isEmpty();
	}

	private List<String> keys(String prefix) {
		List<String> keys = new ArrayList<>();
		this.s3.listObjectsV2Paginator((list) -> list.bucket(this.bucket).prefix(prefix))
			.contents()
			.stream()
			.map(S3Object::key)
			.forEach(keys::add);
		return keys;
	}

	private static String prefix(String contentId, int version) {
		return "content/" + contentId + "/v" + version + "/";
	}

}
