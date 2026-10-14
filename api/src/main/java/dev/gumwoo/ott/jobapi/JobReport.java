package dev.gumwoo.ott.jobapi;

public record JobReport(JobStatus status, FailureCode failureCode, String error) {

}
