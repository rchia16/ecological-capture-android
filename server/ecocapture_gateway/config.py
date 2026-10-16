from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Config:
    storage: Path
    allowed_hosts: tuple[str, ...] = ("localhost", "127.0.0.1")
    max_chunk_bytes: int = 4 * 1024 * 1024
    max_json_bytes: int = 32 * 1024
    max_video_bytes: int = 256 * 1024 * 1024
    max_event_bytes: int = 2 * 1024 * 1024
    max_manifest_bytes: int = 64 * 1024
    max_installation_bytes: int = 512 * 1024 * 1024
    max_total_bytes: int = 1024 * 1024 * 1024
    min_free_bytes: int = 64 * 1024 * 1024
    max_installation_active: int = 4
    max_active: int = 16
    max_records: int = 10000
    max_chunk_records: int = 50000
    requests_per_minute: int = 120
    max_inflight: int = 16
    staging_ttl_seconds: int = 24 * 3600

    def __post_init__(self):
        if not self.allowed_hosts or any(not isinstance(h, str) or not h or any(c in h for c in "/*@\\:") for h in self.allowed_hosts):
            raise ValueError("INVALID_HOST_CONFIGURATION")
        if any(type(v) is not int or v <= 0 for k, v in vars(self).items() if k not in {"storage", "allowed_hosts"}):
            raise ValueError("INVALID_LIMIT_CONFIGURATION")
        if self.max_chunk_bytes > 16 * 1024 * 1024 or self.max_json_bytes > 1024 * 1024:
            raise ValueError("UNSAFE_REQUEST_LIMIT")
