#!/usr/bin/env python3
"""Live check of the subtitle path used by NontonWokProvider.loadLinks()."""
import json
import urllib.error
import urllib.request

BASE = "https://nonton.work"
UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
SID, SLUG = "2447882850302054880", "m-i-a-8GHK5jqkPU2"


def fetch(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    return urllib.request.urlopen(req, timeout=30)


def main():
    h = {"User-Agent": UA, "Referer": BASE + "/"}
    streams = json.load(fetch(
        f"{BASE}/api/stream?subjectId={SID}&detailPath={SLUG}&contentType=series&season=1&episode=1", h))
    sid = streams["data"]["streams"][0]["id"]  # key is "id", not "streamId"
    caps = json.load(fetch(
        f"{BASE}/api/captions?subjectId={SID}&detailPath={SLUG}&streamId={sid}&format=mp4", h))
    rows = caps["data"]  # top-level array, not data.list
    assert rows and rows[0]["url"].startswith("/api/captions/file?")
    assert any(r["language"] == "in_id" for r in rows)
    url = BASE + rows[0]["url"]
    body = fetch(url, {"User-Agent": UA}).read()  # browser UA required
    assert body.startswith(b"WEBVTT")
    try:
        fetch(url)
        raise AssertionError("caption file should 403 without User-Agent")
    except urllib.error.HTTPError as e:
        assert e.code == 403
    print(f"OK: {len(rows)} subtitle tracks, first VTT {len(body)} bytes")


if __name__ == "__main__":
    main()
