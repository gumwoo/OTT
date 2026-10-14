package dev.gumwoo.ott.playback;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;
import software.amazon.awssdk.services.cloudfront.utils.SigningHashAlgorithm;

import org.springframework.http.ResponseCookie;

class SignedCookieSigner {

	private final CloudFrontUtilities cloudFront = CloudFrontUtilities.create();

	private final String resourceBaseUrl;

	private final String keyPairId;

	private final PrivateKey privateKey;

	SignedCookieSigner(String resourceBaseUrl, String keyPairId, PrivateKey privateKey) {
		this.resourceBaseUrl = resourceBaseUrl.replaceAll("/+$", "");
		this.keyPairId = keyPairId;
		this.privateKey = privateKey;
	}

	// 경계에 슬래시를 둬서 movie-1000이나 v20과 매칭되지 않게 한다 (design.md §5)
	static String versionPath(String contentId, int version) {
		return "/content/" + contentId + "/v" + version + "/";
	}

	List<ResponseCookie> sign(String contentId, int version, Instant expiresAt) {
		String path = versionPath(contentId, version);
		// hashAlgorithm을 빼면 SDK 기본값 SHA1로 서명된다
		CustomSignerRequest request = CustomSignerRequest.builder()
			.resourceUrl(this.resourceBaseUrl + path + "master.m3u8")
			.resourceUrlPattern(this.resourceBaseUrl + path + "*")
			.privateKey(this.privateKey)
			.keyPairId(this.keyPairId)
			.expirationDate(expiresAt)
			.hashAlgorithm(SigningHashAlgorithm.SHA256)
			.build();
		CookiesForCustomPolicy cookies = this.cloudFront.getCookiesForCustomPolicy(request);
		String hashAlgorithm = Objects.requireNonNull(cookies.hashAlgorithmHeaderValue(),
				"hash algorithm cookie missing");
		List<String> headers = List.of(cookies.policyHeaderValue(), cookies.signatureHeaderValue(),
				cookies.keyPairIdHeaderValue(), hashAlgorithm);
		return headers.stream().map((header) -> toCookie(header, path)).toList();
	}

	// 만료는 정책의 DateLessThan이 집행하므로 세션 쿠키로 둔다 (design.md §5)
	private static ResponseCookie toCookie(String header, String path) {
		int separator = header.indexOf('=');
		return ResponseCookie.from(header.substring(0, separator), header.substring(separator + 1))
			.path(path)
			.httpOnly(true)
			.secure(true)
			.sameSite("Lax")
			.build();
	}

}
