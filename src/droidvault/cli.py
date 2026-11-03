"""Command-line interface for DroidVault's no-root APK MVP."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from . import __version__
from .adb import (
    AdbError, list_devices, list_packages, package_apk_paths,
    package_version, run_adb,
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command_devices(_args: argparse.Namespace) -> int:
    devices = list_devices()
    if not devices:
        print("Устройства не найдены. Проверьте USB debugging и кабель.")
        return 0
    for device in devices:
        suffix = f" ({device.model})" if device.model else ""
        print(f"{device.serial}\t{device.state}{suffix}")
    return 0


def command_list(args: argparse.Namespace) -> int:
    for package in list_packages(args.serial, not args.all):
        print(package)
    return 0


def command_backup(args: argparse.Namespace) -> int:
    package = args.package
    destination = Path(args.output).expanduser().resolve() / package
    if destination.exists() and any(destination.iterdir()):
        raise AdbError(f"Папка уже существует и не пуста: {destination}")
    destination.mkdir(parents=True, exist_ok=True)

    remote_paths = package_apk_paths(package, args.serial)
    apk_entries = []
    try:
        for index, remote_path in enumerate(remote_paths):
            filename = "base.apk" if index == 0 else f"split_{index:02d}.apk"
            local_path = destination / filename
            run_adb("pull", remote_path, str(local_path), serial=args.serial, timeout=600)
            if not local_path.is_file() or local_path.stat().st_size == 0:
                raise AdbError(f"ADB не сохранил корректный APK: {remote_path}")
            apk_entries.append({
                "file": filename,
                "source_path": remote_path,
                "size": local_path.stat().st_size,
                "sha256": sha256_file(local_path),
            })
    except Exception:
        shutil.rmtree(destination, ignore_errors=True)
        raise

    try:
        manufacturer = run_adb(
            "shell", "getprop", "ro.product.manufacturer", serial=args.serial
        )
        model = run_adb("shell", "getprop", "ro.product.model", serial=args.serial)
    except AdbError:
        manufacturer, model = None, None

    manifest = {
        "format": "droidvault-apk-backup",
        "format_version": 1,
        "tool_version": __version__,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "package": package,
        **package_version(package, args.serial),
        "device": {"manufacturer": manufacturer, "model": model},
        "backup_scope": "apk_only_no_root",
        "contains_private_app_data": False,
        "apks": apk_entries,
    }
    (destination / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Готово: {destination}")
    print(f"Сохранено APK: {len(apk_entries)}. Данные приложения не включены.")
    return 0


def load_manifest(folder: Path) -> dict:
    manifest_path = folder / "manifest.json"
    if not manifest_path.is_file():
        raise AdbError(f"Не найден manifest.json: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AdbError(f"Не удалось прочитать manifest.json: {exc}") from exc
    if manifest.get("format") != "droidvault-apk-backup":
        raise AdbError("Неизвестный формат резервной копии.")
    if manifest.get("format_version") != 1:
        raise AdbError("Эта версия формата резервной копии не поддерживается.")
    return manifest


def command_verify(args: argparse.Namespace) -> int:
    folder = Path(args.folder).expanduser().resolve()
    manifest = load_manifest(folder)
    failed = False
    for item in manifest.get("apks", []):
        path = (folder / item["file"]).resolve()
        if folder not in path.parents:
            raise AdbError("Недопустимый путь файла в манифесте.")
        if not path.is_file():
            print(f"НЕТ ФАЙЛА: {item['file']}")
            failed = True
            continue
        actual = sha256_file(path)
        if actual != item.get("sha256"):
            print(f"ОШИБКА ХЕША: {item['file']}")
            failed = True
        else:
            print(f"OK  {item['file']}")
    if failed:
        return 2
    print(f"Проверка завершена: {manifest.get('package')} — OK")
    return 0


def command_restore(args: argparse.Namespace) -> int:
    folder = Path(args.folder).expanduser().resolve()
    manifest = load_manifest(folder)
    apk_paths = []
    for item in manifest.get("apks", []):
        path = (folder / item["file"]).resolve()
        if folder not in path.parents:
            raise AdbError("Недопустимый путь файла в манифесте.")
        if not path.is_file() or sha256_file(path) != item.get("sha256"):
            raise AdbError(f"APK отсутствует или не прошёл проверку SHA-256: {path}")
        apk_paths.append(str(path))
    if not apk_paths:
        raise AdbError("В манифесте нет APK для установки.")
    print(f"Установка {manifest.get('package')} ({len(apk_paths)} APK)...")
    run_adb("install-multiple", "-r", *apk_paths, serial=args.serial, timeout=900)
    print("Установка завершена. Данные и учётные записи приложения не восстановлены.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="droidvault",
        description="Резервное копирование APK Android через ADB (без root).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    devices = sub.add_parser("devices", help="Показать подключённые устройства")
    devices.set_defaults(func=command_devices)

    packages = sub.add_parser("list", help="Список установленных пакетов")
    packages.add_argument("--serial", help="Серийный номер устройства")
    packages.add_argument("--all", action="store_true", help="Включая системные пакеты")
    packages.set_defaults(func=command_list)

    backup = sub.add_parser("backup", help="Сохранить APK приложения")
    backup.add_argument("package", help="Имя пакета, например com.example.app")
    backup.add_argument("--output", default="./droidvault-backups", help="Каталог копий")
    backup.add_argument("--serial", help="Серийный номер устройства")
    backup.set_defaults(func=command_backup)

    verify = sub.add_parser("verify", help="Проверить SHA-256 резервной копии")
    verify.add_argument("folder", help="Папка резервной копии приложения")
    verify.set_defaults(func=command_verify)

    restore = sub.add_parser("restore", help="Установить APK на подключённое устройство")
    restore.add_argument("folder", help="Папка резервной копии приложения")
    restore.add_argument("--serial", help="Серийный номер устройства")
    restore.set_defaults(func=command_restore)
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    try:
        exit_code = args.func(args)
    except AdbError as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        exit_code = 1
    except KeyboardInterrupt:
        print("\nОперация отменена.", file=sys.stderr)
        exit_code = 130
    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()
