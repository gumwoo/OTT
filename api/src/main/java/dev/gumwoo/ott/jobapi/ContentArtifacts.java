package dev.gumwoo.ott.jobapi;

import java.util.List;

// 게시 전에 API가 직접 산출물을 확인한다. Job의 자기 보고만 믿지 않는다 (design.md §4)
public interface ContentArtifacts {

	List<String> problems(String contentId, int version);

	// 지운 것이 있으면 true. 없으면 아무것도 하지 않는다
	boolean delete(String contentId, int version);

}
