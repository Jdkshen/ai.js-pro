"""Create a companion GitHub update manifest with GH-Proxy APK URLs.

Input must be the release's GitHub update.json (absolute GitHub asset URLs).
Upload the output as update-gh-proxy.json alongside every public release.
"""

import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit


def create_manifest(source, proxy):
    prefix = proxy.rstrip("/") + "/"
    parsed = urlsplit(prefix)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or parsed.path != "/"):
        raise ValueError("Proxy must be an HTTPS origin, e.g. https://gh-proxy.com/")

    # Round-trip creates an independent copy; versions, hashes and history stay intact.
    result = json.loads(json.dumps(source))

    def accelerate(url):
        resource = urlsplit(url)
        if (resource.scheme != "https" or resource.netloc != "github.com"
                or "/releases/download/" not in resource.path):
            raise ValueError("Expected an absolute, fixed-version GitHub release asset URL")
        return prefix + url

    count = 0
    if result.get("apkUrl"):
        result["apkUrl"] = accelerate(result["apkUrl"])
        count += 1
    for asset in result.get("assets", []):
        asset["url"] = accelerate(asset["url"])
        count += 1
    if not count:
        raise ValueError("Manifest has no APK downloads")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--proxy", default="https://gh-proxy.com/")
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error("Use a separate output file; preserve the direct manifest")
    result = create_manifest(json.loads(args.source.read_text("utf-8-sig")), args.proxy)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(f"Created {args.output}: {result['versionName']} / {result['versionCode']}")


if __name__ == "__main__":
    main()
