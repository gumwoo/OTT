package dev.gumwoo.ott.jobapi;

public enum FailureCode {

	VERSION_DIRTY, DISK_FULL, SOURCE_HASH_MISMATCH, VALIDATION_FAILED, TRANSCODE_FAILED, UPLOAD_FAILED, TERMINATED,

	// 운영 CronJob만 확정한다. Job은 보고할 수 없다
	LOST, NOT_STARTED;

	boolean reportableByJob() {
		return this != LOST && this != NOT_STARTED;
	}

}
