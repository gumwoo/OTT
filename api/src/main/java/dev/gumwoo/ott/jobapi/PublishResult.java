package dev.gumwoo.ott.jobapi;

// currentVersion은 응답 시점의 공개 버전이다. 재요청이면 그사이 바뀌었을 수 있다
public record PublishResult(JobStatus status, SupersededReason reason, int version, Integer currentVersion) {

}
