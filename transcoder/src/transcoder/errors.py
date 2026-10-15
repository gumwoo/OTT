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


class UploadFailedError(TranscodeError):
    failure_code = "UPLOAD_FAILED"


class TerminatedError(TranscodeError):
    failure_code = "TERMINATED"


# API가 이미 FAILED로 확정했다(409 JOB_FAILED). 다시 보고하지 않고 멈춘다
class LeaseLostError(TranscodeError):
    failure_code = "LOST"


class ConfigError(TranscodeError):
    failure_code = "CONFIG_INVALID"


class ApiUnavailableError(TranscodeError):
    failure_code = "API_UNAVAILABLE"


class ApiRejectedError(TranscodeError):
    failure_code = "API_REJECTED"


# API가 받는 실패 코드 (api FailureCode). 나머지는 TRANSCODE_FAILED로 보고한다
REPORTABLE_CODES = frozenset(
    {
        "VERSION_DIRTY",
        "DISK_FULL",
        "SOURCE_HASH_MISMATCH",
        "VALIDATION_FAILED",
        "TRANSCODE_FAILED",
        "UPLOAD_FAILED",
        "TERMINATED",
    }
)
