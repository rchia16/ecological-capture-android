class GatewayError(Exception):
    def __init__(self, status, code, **safe_fields):
        super().__init__(code)
        self.status = status
        self.code = code
        self.safe_fields = safe_fields


def require(condition, status=400, code="INVALID_REQUEST", **fields):
    if not condition:
        raise GatewayError(status, code, **fields)
