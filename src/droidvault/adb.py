"""Small, defensive wrapper around the Android Debug Bridge."""
from __future__ import annotations

import re
import shutil
import subprocess
from dataclasses import dataclass


class AdbError(RuntimeError):
    """Raised when ADB is missing, unavailable, or returns an error."""


@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    model: str | None = None


def require_adb() -> str:
    executable = shutil.which("adb")
    if not executable:
        raise AdbError(
            "adb не найден. Установите Android Platform Tools и добавьте adb в PATH."
        )
    return executable


def run_adb(*args: str, serial: str | None = None, timeout: int = 120) -> str:
    command = [require_adb()]
    if serial:
        command += ["-s", serial]
    command.extend(args)
    try:
        result = subprocess.run(
            command, check=False, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout
        )
    except subprocess.TimeoutExpired as exc:
        raise AdbError(f"ADB: превышено время ожидания команды: {' '.join(args)}") from exc
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise AdbError(detail or f"ADB завершился с кодом {result.returncode}")
    return result.stdout.strip()


def list_devices() -> list[Device]:
    output = run_adb("devices", "-l")
    devices: list[Device] = []
    for line in output.splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        model_match = re.search(r"\bmodel:(\S+)", line)
        devices.append(Device(serial, state, model_match.group(1) if model_match else None))
    return devices


def list_packages(serial: str | None = None, third_party_only: bool = True) -> list[str]:
    args = ["shell", "pm", "list", "packages"]
    if third_party_only:
        args.append("-3")
    output = run_adb(*args, serial=serial)
    packages = []
    for line in output.splitlines():
        value = line.strip()
        if value.startswith("package:"):
            name = value[len("package:"):].strip()
            if name:
                packages.append(name)
    return sorted(set(packages))


def package_apk_paths(package: str, serial: str | None = None) -> list[str]:
    output = run_adb("shell", "pm", "path", package, serial=serial)
    paths = [
        line.strip().removeprefix("package:")
        for line in output.splitlines()
        if line.strip().startswith("package:")
    ]
    if not paths:
        raise AdbError(f"Android не вернул APK-пути для пакета {package}")
    return paths


def package_version(package: str, serial: str | None = None) -> dict[str, str | None]:
    """Best-effort version extraction; output format varies by Android vendor."""
    output = run_adb("shell", "dumpsys", "package", package, serial=serial)
    name = re.search(r"versionName=([^\s]+)", output)
    code = re.search(r"versionCode=(\d+)", output)
    return {
        "version_name": name.group(1) if name else None,
        "version_code": code.group(1) if code else None,
    }
