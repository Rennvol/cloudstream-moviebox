"""Cek cepat API Dlidix + susunan provider. Jalankan: python3 check_dlidix.py"""
import json
import pathlib
import urllib.error
import urllib.request

API = "https://z3.idlixku.com/api"
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"


def get(path):
    req = urllib.request.Request(API + path, headers={"User-Agent": UA, "Accept": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=20))


def demo():
    home = get("/homepage")
    assert any(section["data"] for section in home["above"] + home["below"]), "homepage kosong"
    trend = get("/movies?sort=latest&page=1")
    assert trend["data"][0]["slug"] and trend["data"][0]["posterPath"].startswith("/"), "shape film salah"
    found = get("/search?q=avengers")
    assert any(x["slug"] == "avengers-endgame-2019" for x in found["results"]), "search gagal"
    movie = get("/movies/avengers-endgame-2019")
    assert movie["id"] and movie["title"] and movie["genres"], "detail film salah"
    series = get("/series/stranger-things-2016")
    assert series["numberOfSeasons"] >= 1, "detail series salah"
    season = get("/series/stranger-things-2016/season/1")
    ep = season["season"]["episodes"][0]
    assert ep["id"] and ep["episodeNumber"] == 1, "episode salah"
    try:
        get("/watch/play-info/movie/" + movie["id"])
    except urllib.error.HTTPError as e:
        assert e.code == 403, f"play-info HTTP {e.code}"
    # Kontrak yang dipakai provider: play-info (3 host) -> redeem -> HLS + subtitle.
    src = pathlib.Path("Dlidix/src/main/kotlin/com/rennv/dlidix/DlidixProvider.kt").read_text()
    for token in ('"claim"', '"redeemUrl"', '"subtitles"', 'RESOLUTION=', 'newSubtitleFile', 'PLAY_HOSTS', 'addEpisodes'):
        assert token in src, f"provider kehilangan {token}"
    assert '"gateToken"' not in src, "provider masih pakai gate lama"
    print("OK: homepage, search, detail, season, play-info 403, kontrak episode/subtitle/1080p")


if __name__ == "__main__":
    demo()
