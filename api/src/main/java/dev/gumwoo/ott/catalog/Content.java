package dev.gumwoo.ott.catalog;

import dev.gumwoo.ott.subscription.Plan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 버전 할당·게시 컬럼은 변환 단계에서 매핑한다
@Entity
@Table(name = "contents")
class Content {

	@Id
	private String id;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Plan requiredPlan;

	@Column(nullable = false)
	private int durationSec;

	private Integer currentVersion;

	protected Content() {
	}

	CatalogItem item() {
		return new CatalogItem(this.id, this.title, this.requiredPlan, this.durationSec);
	}

	ContentDetail detail(Plan viewerPlan) {
		return new ContentDetail(this.id, this.title, this.description, this.requiredPlan, this.durationSec,
				viewerPlan.covers(this.requiredPlan));
	}

	PlayableContent playable() {
		return new PlayableContent(this.id, this.requiredPlan, this.currentVersion);
	}

}
