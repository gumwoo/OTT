class TranscodeError(Exception):
    failure_code = "TRANSCODE_FAILED"


class VersionDirtyError(TranscodeError):
    failure_code = "VERSION_DIRTY"


class DiskFullError(TranscodeError):
    failure_code = "DISK_FULL"


class SourceHashMismatchError(TranscodeError):
    failure_code = "SOURCE_HASH_MISMATCH"


class ValidationFailedError(TranscodeError):
    failure_code = "VALIDATION_FAILED"
