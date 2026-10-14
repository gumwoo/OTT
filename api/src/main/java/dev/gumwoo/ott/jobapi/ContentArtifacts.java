package dev.gumwoo.ott.jobapi;

import java.util.List;

// 게시 전에 API가 직접 산출물을 확인한다. Job의 자기 보고만 믿지 않는다 (design.md §4)
public interface ContentArtifacts {

	List<String> problems(String contentId, int version);

}
