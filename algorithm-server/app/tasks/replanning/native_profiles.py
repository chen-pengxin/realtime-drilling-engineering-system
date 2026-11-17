"""Compatibility import for the single native profile registration hook."""

from app import ensure_native_profiles as _ensure_native_profiles


def ensure_native_profiles() -> tuple[str, ...]:
    """Return the application profile set without applying a second patch."""
    return tuple(_ensure_native_profiles())

