"""Local transport adapter for the existing verified receiver; no network or cloud SDK."""
import os
import shutil

from ecocapture_gateway.verified_receiver import Receiver


class LocalPackages:
    def __init__(self, store):
        self.store = store

    def pending(self, study):
        if study != self.store.direct.study_id: raise ValueError("STUDY_MISMATCH")
        return self.store.pending()

    def download(self, row, destination):
        with self.store.cipher(row["snapshot_id"]).open("rb") as source, destination.open("xb") as output:
            shutil.copyfileobj(source, output, 128 * 1024)
            output.flush(); os.fsync(output.fileno())

    def receipt(self, body):
        return self.store.receive_receipt(body)


def receiver(store, decrypt=None):
    config = store.direct
    return Receiver(store.root / "received", config.study_id, config.age_tool, config.identity, LocalPackages(store), decrypt=decrypt)
