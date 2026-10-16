class MockFileSender:
    """Atomic non-content intent ledger only. No network, email, credential or encryption API."""
    def record(self, connection, transfer, manifest_sha256):
        connection.execute("INSERT INTO mock_intents VALUES (?, ?, ?)",
                           (transfer["id"], transfer["snapshot_id"], manifest_sha256))
