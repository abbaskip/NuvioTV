"""Validate signed Gradle ABI outputs and stage versioned release assets."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
PACKAGE = "io.github.abbaskip.nuvioreseq"


def native_libraries(apk: Path) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                result.setdefault(parts[1], set()).add(parts[2])
    return result


def validate_native_outputs(outputs: dict[str, Path]) -> None:
    universal = native_libraries(outputs["universal"])
    if set(universal) != set(ABIS):
        raise ValueError(f"Universal APK ABI mismatch: {sorted(universal)}")
    # Every build must have the complete native feature set, including playback engines.
    reference = universal[ABIS[0]]
    if not reference or any(libs != reference for libs in universal.values()):
        raise ValueError("Universal APK does not have the same native libraries for every ABI")
    for abi in ABIS:
        if native_libraries(outputs[abi]) != {abi: universal[abi]}:
            raise ValueError(f"{abi} APK has missing or unexpected native libraries")


def command(tool: str, *args: str) -> str:
    return subprocess.check_output([tool, *args], text=True, encoding="utf-8", errors="replace", stderr=subprocess.STDOUT)


def certificate(apksigner: str, apk: Path) -> str:
    output = command(apksigner, "verify", "--verbose", "--print-certs", str(apk))
    # v3.1 signatures use SDK-range labels and may repeat one certificate.
    # Compare unique application signers, excluding source-stamp certificates.
    digests = {digest.lower() for digest in re.findall(
        r"^Signer (?:#\d+|\(minSdkVersion=[^\r\n]+\)) certificate SHA-256 digest: ([0-9a-fA-F]{64})[ \t]*$",
        output, re.MULTILINE)}
    if len(digests) != 1:
        raise ValueError(f"Expected one unique verified signer, found {len(digests)}: {apk.name}")
    return next(iter(digests))


def identity(aapt: str, apk: Path) -> tuple[str, int, str]:
    output = command(aapt, "dump", "badging", str(apk))
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match:
        raise ValueError(f"Cannot read package metadata: {apk.name}")
    return match[1], int(match[2]), match[3]


def stage(apk_dir: Path, output_dir: Path, version: str, apksigner: str, aapt: str,
          reference_apk: Path | None = None) -> list[Path]:
    metadata = json.loads((apk_dir / "output-metadata.json").read_text(encoding="utf-8"))
    outputs = {}
    for element in metadata["elements"]:
        filters = element["filters"]
        abi = next((f["value"] for f in filters if f["filterType"] == "ABI"), "universal")
        if abi in outputs:
            raise ValueError(f"Duplicate ABI output: {abi}")
        outputs[abi] = apk_dir / element["outputFile"]
    if set(outputs) != {*ABIS, "universal"}:
        raise ValueError(f"Missing or unexpected APK variants: {sorted(outputs)}")
    validate_native_outputs(outputs)
    expected_identity = identity(aapt, outputs["universal"])
    expected_signer = certificate(apksigner, outputs["universal"])
    if expected_identity[0] != PACKAGE or expected_identity[2] != version:
        raise ValueError(f"Unexpected release identity: {expected_identity}")
    if reference_apk:
        previous = identity(aapt, reference_apk)
        if previous[0] != PACKAGE or previous[1] >= expected_identity[1]:
            raise ValueError("Release must upgrade the previous package/version code")
        if certificate(apksigner, reference_apk) != expected_signer:
            raise ValueError("Release certificate differs from the previous published APK")
    for apk in outputs.values():
        if identity(aapt, apk) != expected_identity or certificate(apksigner, apk) != expected_signer:
            raise ValueError(f"Inconsistent APK identity or signing: {apk.name}")
    output_dir.mkdir(parents=True, exist_ok=True)
    assets = []
    for abi in ("universal", *ABIS):
        target = output_dir / f"Nuvio-ReSeq-v{version}-{abi}.apk"
        shutil.copy2(outputs[abi], target)
        assets.append(target)
    sums = output_dir / "SHA256SUMS.txt"
    sums.write_bytes("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in assets).encode("utf-8"))
    guidance = output_dir / "DOWNLOADS.md"
    guidance.write_text(
        "## APK downloads\n\n"
        "Choose **universal** if unsure; it works on all supported architectures and is used by in-app updates.\n\n"
        "Smaller downloads: **armeabi-v7a** (ARM32), **arm64-v8a** (ARM64), **x86** (32-bit Intel), "
        "**x86_64** (64-bit Intel). Choose the Android OS ABI, which can differ from the CPU hardware.\n\n"
        "All APKs use the same application ID, version code and release signing identity. Install over the existing app to preserve data. "
        "SHA-256 checksums are in `SHA256SUMS.txt`.\n", encoding="utf-8")
    return [*assets, sums, guidance]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--reference-apk", type=Path)
    args = parser.parse_args()
    for asset in stage(**vars(args)):
        print(asset)
