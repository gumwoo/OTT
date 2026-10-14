package dev.gumwoo.ott.jobapi;

import java.util.Set;

// design.md §4 "작업 상태와 게시 검증"
public enum JobStatus {

	REQUESTED, RUNNING, VALIDATED, UPLOADED, PUBLISHED, PUBLISHED_SUPERSEDED, FAILED;

	static final Set<JobStatus> REPORTABLE = Set.of(RUNNING, VALIDATED, UPLOADED, FAILED);

	boolean canMoveTo(JobStatus next) {
		return switch (this) {
			case REQUESTED -> next == RUNNING || next == FAILED;
			case RUNNING -> next == VALIDATED || next == FAILED;
			case VALIDATED -> next == UPLOADED || next == FAILED;
			case UPLOADED -> next == FAILED;
			case PUBLISHED, PUBLISHED_SUPERSEDED, FAILED -> false;
		};
	}

	boolean published() {
		return this == PUBLISHED || this == PUBLISHED_SUPERSEDED;
	}

}
