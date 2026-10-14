package dev.gumwoo.ott.catalog.web;

import java.util.List;

import dev.gumwoo.ott.catalog.CatalogItem;
import dev.gumwoo.ott.catalog.CatalogService;
import dev.gumwoo.ott.catalog.ContentDetail;
import dev.gumwoo.ott.common.security.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

	private final CatalogService catalog;

	CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/api/contents")
	List<CatalogItem> list() {
		return this.catalog.list();
	}

	@GetMapping("/api/contents/{id}")
	ContentDetail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
		return this.catalog.detail(id, user.id());
	}

}
