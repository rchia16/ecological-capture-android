from dataclasses import dataclass
import json
from pathlib import Path
import re

from ecocapture_gateway.archive_encryptor import validate_recipient
from ecocapture_gateway.config import Config
from ecocapture_gateway.errors import require


def identifier(value):
    require(type(value) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value) is not None)
    return value


@dataclass(frozen=True)
class DirectConfig:
    gateway: Config
    study_id: str
    recipients: tuple[str, str]
    key_versions: tuple[str, str]
    age_tool: Path
    identity: Path

    def __post_init__(self):
        identifier(self.study_id)
        require(len(self.recipients) == len(set(self.recipients)) == 2)
        require(len(self.key_versions) == len(set(self.key_versions)) == 2)
        for key in self.recipients: validate_recipient(key)
        for version in self.key_versions: identifier(version)

    @classmethod
    def load(cls, path):
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        gateway = raw.pop("gateway")
        gateway["storage"] = Path(gateway["storage"])
        gateway["allowed_hosts"] = tuple(gateway["allowed_hosts"])
        for field in ("recipients", "key_versions"): raw[field] = tuple(raw[field])
        for field in ("age_tool", "identity"): raw[field] = Path(raw[field])
        return cls(gateway=Config(**gateway), **raw)
