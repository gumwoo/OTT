package dev.gumwoo.ott.jobapi;

public record PublishRequest(String contentId, int version, String sourceHash, String profileVersion) {

}
