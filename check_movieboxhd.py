#!/usr/bin/env python3
"""Live-assert the MovieBoxHD v25 schemas: episode titles (resource pager),
dub labels (detail.dubs lanName), recommendations (H5 subject/recommend).
Run after any change to those paths: python3 check_movieboxhd.py
"""
import base64, hashlib, hmac, json, time, urllib.parse, urllib.request, uuid

KEY = base64.b64decode(base64.b64decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="))
MODEL = "22101316G"
UA = f"com.community.oneroom/50020042 (Linux; U; Android 14; en_IN; {MODEL}; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
# Random identity per run: the BFF soft-bans fixed device_ids (406 "find no
# content" on get/search/resource while season-info stays 200).
CLIENT_INFO = json.dumps({"package_name": "com.community.oneroom", "version_name": "3.0.03.0529.03",
    "version_code": 50020042, "os": "android", "os_version": "14",
    "device_id": uuid.uuid4().hex, "install_store": "official",
    "gaid": str(uuid.uuid4()), "brand": "xiaomi", "model": MODEL,
    "system_language": "en", "net": "NETWORK_WIFI", "region": "US", "timezone": "Asia/Jakarta",
    "sp_code": ""}, separators=(",", ":"))
HOSTS = ["https://api6.aoneroom.com", "https://api5.aoneroom.com", "https://api4.aoneroom.com",
         "https://api4sg.aoneroom.com", "https://api3.aoneroom.com", "https://api6sg.aoneroom.com",
         "https://api.inprovider.com"]
bearer = None

def md5(b): return hashlib.md5(b).hexdigest()

def canonical_url(url):
    p = urllib.parse.urlsplit(url)
    pairs = urllib.parse.parse_qsl(p.query, keep_blank_values=True)
    names = sorted({k for k, _ in pairs})
    q = "&".join(f"{k}={v}" for k in names for k2, v in pairs if k2 == k)
    return f"{p.path}?{q}" if names else p.path

def request(path_q, method="GET", body=None, bootstrap=False):
    global bearer
    for host in HOSTS:
        url = host + path_q
        ts = int(time.time() * 1000)
        ctype = "application/json; charset=utf-8" if body is not None else "application/json"
        b = body.encode() if body else None
        canonical = "\n".join([method.upper(), "application/json", ctype,
            str(len(b)) if b else "", str(ts), md5(b[:102400]) if b else "", canonical_url(url)])
        sig = "|".join([str(ts), "2", base64.b64encode(hmac.new(KEY, canonical.encode(), hashlib.md5).digest()).decode()])
        headers = {"User-Agent": UA, "Accept": "application/json", "Content-Type": ctype,
            "x-client-token": f"{ts},{md5(str(ts)[::-1].encode())}", "x-tr-signature": sig,
            "x-client-info": CLIENT_INFO, "x-client-status": "0"}
        if not bootstrap and bearer: headers["Authorization"] = f"Bearer {bearer}"
        req = urllib.request.Request(url, data=b, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                x_user = r.headers.get("x-user")
                if x_user:
                    try: bearer = json.loads(x_user).get("token") or x_user
                    except Exception: bearer = x_user
                return json.loads(r.read())
        except Exception:
            continue
    raise AssertionError(f"all hosts failed: {path_q}")

def data_of(root): return root.get("data") or root.get("subject") or root

# bootstrap guest token
request("/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=", bootstrap=True)
assert bearer, "no guest token"

# find a series with dubs
sid = None
for kw in ("squid game", "stranger things", "money heist"):
    d = data_of(request("/wefeed-mobile-bff/subject-api/search/v2", "POST",
        json.dumps({"keyword": kw, "page": 1, "perPage": 10}, separators=(",", ":"))))
    for r in d.get("results", []):
        for s in r.get("subjects", []):
            if s.get("subjectType") in (2, 7):
                sid = s["subjectId"]; break
        if sid: break
    if sid: break
assert sid, "no series found in search"

det = data_of(request(f"/wefeed-mobile-bff/subject-api/get?subjectId={sid}"))
dubs = det.get("dubs") or []
assert any(x.get("lanName") and not x.get("original") for x in dubs), "dubs lack lanName labels"

# 1) episode titles: resource pager rows carry se/ep/title
res = data_of(request(f"/wefeed-mobile-bff/subject-api/resource?subjectId={sid}&se=1&ep=1&page=1"))
rows = res.get("list") or []
assert rows, "resource list empty"
assert all("se" in i and "ep" in i and i.get("title") for i in rows), "resource rows lack se/ep/title"
assert isinstance(res.get("pager", {}).get("hasMore"), bool), "pager.hasMore missing"

# 3) recommendations: H5 unsigned endpoint
req = urllib.request.Request(f"https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/recommend?subjectId={sid}",
    headers={"User-Agent": "Mozilla/5.0"})
items = json.loads(urllib.request.urlopen(req, timeout=20).read())["data"].get("movieItems") or []
assert items, "recommend movieItems empty"
assert all(i.get("id") and i.get("name") and str(i.get("cover", "")).startswith("http") for i in items), \
    "recommend items lack id/name/cover"

print(f"OK series={sid} dubs={len(dubs)} epRows={len(rows)} recs={len(items)}")
