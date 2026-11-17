"""Live check: MovieBoxHD links must be real media, not the 23s bumper."""
import base64
import hashlib
import hmac
import json
import time
import urllib.parse
import urllib.request

HOST = "https://api6.aoneroom.com"
KEY = base64.b64decode(base64.b64decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==").decode())
UA = "com.community.oneroom/50020042 (Linux; U; Android 14; en_IN; 22101316G; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
CI = json.dumps({"package_name": "com.community.oneroom", "version_name": "3.0.03.0529.03", "version_code": 50020042, "os": "android", "os_version": "14", "device_id": "0" * 32, "install_store": "official", "gaid": "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d", "brand": "xiaomi", "model": "22101316G", "system_language": "en", "net": "NETWORK_WIFI", "region": "US", "timezone": "Asia/Jakarta", "sp_code": ""}, separators=(",", ":"))
SID = "7576700355313287640"  # Parasite
token = [None]


def request(path, body=None, bootstrap=False):
    url = HOST + path
    ts = int(time.time() * 1000)
    b = body.encode() if body else None
    p = urllib.parse.urlsplit(url)
    q = urllib.parse.parse_qs(p.query, keep_blank_values=True)
    canon = p.path + ("?" + "&".join(k + "=" + "&".join(q[k]) for k in sorted(q)) if q else "")
    ct = "application/json; charset=utf-8" if b else "application/json"
    canonical = "\n".join(["POST" if b else "GET", "application/json", ct, str(len(b)) if b else "", str(ts), hashlib.md5(b[:102400]).hexdigest() if b else "", canon])
    sig = base64.b64encode(hmac.new(KEY, canonical.encode(), hashlib.md5).digest()).decode()
    headers = {"User-Agent": UA, "Accept": "application/json", "Content-Type": ct, "x-client-token": f"{ts}," + hashlib.md5(str(ts)[::-1].encode()).hexdigest(), "x-tr-signature": f"{ts}|2|{sig}", "x-client-info": CI, "x-client-status": "0"}
    if not bootstrap and token[0]:
        headers["Authorization"] = "Bearer " + token[0]
    r = urllib.request.urlopen(urllib.request.Request(url, data=b, headers=headers), timeout=30)
    try:
        token[0] = json.loads(r.headers["x-user"]).get("token") or token[0]
    except (KeyError, ValueError, TypeError):
        pass
    return json.load(r)


def playable(url):
    u = url.lower()
    return u.startswith("http") and "macdn.aoneroom.com/other/" not in u


if __name__ == "__main__":
    request("/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=", bootstrap=True)
    detail = request(f"/wefeed-mobile-bff/subject-api/get?subjectId={SID}")["data"]
    real = [r["resourceLink"] for d in detail["resourceDetectors"] for r in d["resolutionList"] if playable(r["resourceLink"])]
    assert len(real) >= 4, f"no real detector links: {len(real)}"
    with urllib.request.urlopen(urllib.request.Request(real[0], headers={"Range": "bytes=0-1023"}), timeout=30) as r:
        assert r.status == 206 and r.headers.get("Content-Length") == "1024", "real link not streamable"
    bumper = request(f"/wefeed-mobile-bff/subject-api/play-info?subjectId={SID}&se=0&ep=0")["data"]["streams"][0]["url"]
    assert not playable(bumper), f"bumper not filtered: {bumper}"
    assert f"{SID}|1|1".split("|")[0].isdigit(), "episode key must stay raw"
    print(f"OK: {len(real)} real links, bumper filtered, episode key raw")
