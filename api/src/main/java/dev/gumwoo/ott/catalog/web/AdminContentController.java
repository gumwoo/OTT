package dev.gumwoo.ott.catalog.web;

import dev.gumwoo.ott.catalog.CatalogService;
import dev.gumwoo.ott.catalog.NewContent;
import dev.gumwoo.ott.subscription.Plan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminContentController {

	private final CatalogService catalog;

	AdminContentController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@PostMapping("/api/admin/contents")
	@ResponseStatus(HttpStatus.CREATED)
	void register(@Valid @RequestBody Request request) {
		this.catalog.register(new NewContent(request.id(), request.title(),
				(request.description() != null) ? request.description() : "", request.requiredPlan(),
				request.durationSec()));
	}

	// Job 이름 transcode-{id}-v{n}이 63자 안에 들도록 id는 40자까지
	record Request(@NotBlank @Size(max = 40) @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?") String id,
			@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, @NotNull Plan requiredPlan,
			@Positive int durationSec) {
	}

}
