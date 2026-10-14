package dev.gumwoo.ott.jobapi;

public record RollbackResult(String contentId, int currentVersion, int maxPublishedVersion, int publishEpoch) {

}
