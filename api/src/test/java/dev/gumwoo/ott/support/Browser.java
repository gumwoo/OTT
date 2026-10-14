package dev.gumwoo.ott.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 쿠키를 들고 다니고, 변경 요청에는 웹 앱처럼 XSRF-TOKEN 값을 헤더로 붙인다
public final class Browser {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final HttpClient http = HttpClient.newHttpClient();

	private final String baseUrl;

	private final Map<String, String> cookies = new LinkedHashMap<>();

	private boolean sendCsrfHeader = true;

	private String csrfHeaderOverride;

	private final Map<String, String> extraHeaders = new LinkedHashMap<>();

	public Browser(int port) {
		this.baseUrl = "http://localhost:" + port;
	}

	public Response get(String path) {
		return send("GET", path, null, null);
	}

	public Response post(String path, String json) {
		return send("POST", path, json, "application/json");
	}

	public Response put(String path, String json) {
		return send("PUT", path, json, "application/json");
	}

	public Response send(String method, String path, String body, String contentType) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + path))
			.method(method,
					(body != null) ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
		if (contentType != null) {
			request.header("Content-Type", contentType);
		}
		this.extraHeaders.forEach(request::header);
		if (!this.cookies.isEmpty()) {
			request.header("Cookie",
					this.cookies.entrySet()
						.stream()
						.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
						.collect(Collectors.joining("; ")));
		}
		String token = (this.csrfHeaderOverride != null) ? this.csrfHeaderOverride : this.cookies.get("XSRF-TOKEN");
		if (this.sendCsrfHeader && UNSAFE.contains(method) && token != null) {
			request.header("X-XSRF-TOKEN", token);
		}
		try {
			HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			Response result = new Response(response.statusCode(), response.headers().allValues("Set-Cookie"),
					response.headers().firstValue("Content-Type").orElse(""), response.body());
			result.setCookies().forEach(this::remember);
			return result;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	public Browser withoutCsrfHeader() {
		this.sendCsrfHeader = false;
		return this;
	}

	public Browser header(String name, String value) {
		this.extraHeaders.put(name, value);
		return this;
	}

	// 앱이 메모리에 들고 있던 토큰을 쿠키와 무관하게 보낼 때
	public Browser csrfHeader(String token) {
		this.csrfHeaderOverride = token;
		return this;
	}

	public String cookie(String name) {
		return this.cookies.get(name);
	}

	public void cookie(String name, String value) {
		this.cookies.put(name, value);
	}

	private void remember(String setCookie) {
		String pair = setCookie.split(";", 2)[0];
		String name = pair.substring(0, pair.indexOf('='));
		String value = pair.substring(pair.indexOf('=') + 1);
		if (setCookie.contains("Max-Age=0") || value.isEmpty()) {
			this.cookies.remove(name);
		}
		else {
			this.cookies.put(name, value);
		}
	}

	public record Response(int status, List<String> setCookies, String contentType, String body) {

		public JsonNode json() {
			return JSON.readTree(this.body);
		}

		public Optional<String> setCookie(String name) {
			return this.setCookies.stream().filter((cookie) -> cookie.startsWith(name + "=")).findFirst();
		}

	}

}
