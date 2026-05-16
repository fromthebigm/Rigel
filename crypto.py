"""
crypto.py — Rigel Browser password encryption helpers
"""

import base64
from constants import DATA_DIR


def _get_fernet():
    try:
        from cryptography.fernet import Fernet
        key_path = DATA_DIR / ".key"
        if key_path.exists():
            return Fernet(key_path.read_bytes())
        key = Fernet.generate_key()
        key_path.write_bytes(key)
        key_path.chmod(0o600)
        return Fernet(key)
    except ImportError:
        return None


_fernet = _get_fernet()


def encrypt(s: str) -> str:
    if _fernet:
        return _fernet.encrypt(s.encode()).decode()
    return base64.b64encode(s.encode()).decode()


def decrypt(s: str) -> str:
    try:
        if _fernet:
            return _fernet.decrypt(s.encode()).decode()
        return base64.b64decode(s.encode()).decode()
    except Exception:
        return ""
