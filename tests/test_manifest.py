import hashlib
import json

from droidvault.cli import load_manifest, sha256_file


def test_sha256_file(tmp_path):
    file_path = tmp_path / "sample.apk"
    file_path.write_bytes(b"apk-content")
    assert sha256_file(file_path) == hashlib.sha256(b"apk-content").hexdigest()


def test_load_manifest_accepts_supported_format(tmp_path):
    (tmp_path / "manifest.json").write_text(json.dumps({
        "format": "droidvault-apk-backup",
        "format_version": 1,
        "package": "com.example.app",
        "apks": [],
    }), encoding="utf-8")
    assert load_manifest(tmp_path)["package"] == "com.example.app"


def test_load_manifest_rejects_unknown_format(tmp_path):
    (tmp_path / "manifest.json").write_text(json.dumps({
        "format": "not-droidvault",
        "format_version": 1,
    }), encoding="utf-8")
    try:
        load_manifest(tmp_path)
    except RuntimeError as exc:
        assert "Неизвестный формат" in str(exc)
    else:
        raise AssertionError("Expected unknown format to be rejected")
