"""Helper utilities for the demo Python service."""


class Repository:
    """Simple repository that remembers user access."""

    def __init__(self) -> None:
        self._last = None

    def find(self, name: str) -> str:
        """Returns the user identifier and stores it for diagnostics."""
        self._last = name
        return name.upper()

    def last(self) -> str | None:
        return self._last


def helper(count: int) -> None:
    print("count", count)
