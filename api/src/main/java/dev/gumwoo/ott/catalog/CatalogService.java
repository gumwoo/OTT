package dev.gumwoo.ott.catalog;

import java.util.List;

import dev.gumwoo.ott.common.error.ApiException;
import dev.gumwoo.ott.common.error.ErrorCode;
import dev.gumwoo.ott.subscription.SubscriptionService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

	private final ContentRepository contents;

	private final SubscriptionService subscriptions;

	CatalogService(ContentRepository contents, SubscriptionService subscriptions) {
		this.contents = contents;
		this.subscriptions = subscriptions;
	}

	@Transactional(readOnly = true)
	public List<CatalogItem> list() {
		return this.contents.findByCurrentVersionIsNotNullOrderByTitleAsc().stream().map(Content::item).toList();
	}

	@Transactional(readOnly = true)
	public ContentDetail detail(String id, long viewerId) {
		Content content = this.contents.findByIdAndCurrentVersionIsNotNull(id)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return content.detail(this.subscriptions.get(viewerId).plan());
	}

	// 공개 버전은 변환 작업 게시로만 정해진다
	@Transactional
	public void register(NewContent content) {
		if (this.contents.insertIfAbsent(content.id(), content.title(), content.description(),
				content.requiredPlan().name(), content.durationSec()) == 0) {
			throw new ApiException(ErrorCode.CONTENT_EXISTS);
		}
	}

	// 공개 전 콘텐츠도 돌려준다. 공개 여부는 재생 쪽에서 NOT_PUBLISHED로 판단한다
	@Transactional(readOnly = true)
	public PlayableContent forPlayback(String id) {
		return this.contents.findById(id)
			.map(Content::playable)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
