# cloudstream-moviebox

CloudStream 3 extension for [moviebox.ph](https://moviebox.ph) (mobile BFF API).

## Add to CloudStream

Settings → Extensions → Add repository → paste:

```
https://raw.githubusercontent.com/Rennvol/cloudstream-moviebox/builds/repo.json
```

Then install **MovieBox** from the Rennvol MovieBox repo.

## Build

Every push to `main` builds the `.cs3` files in GitHub Actions and publishes them
to the `builds` branch together with `plugins.json` and `repo.json`.

Local build (needs Android SDK + JDK 17):

```
gradle make makePluginsJson
```

## How it works

The provider signs every request against `api*.aoneroom.com/wefeed-mobile-bff`:

- `x-client-token` = `md5(reverse(timestamp_ms))`
- `x-tr-signature` = `hmac-md5(canonical_string, key)` base64, prefixed `ts|2|`
- a guest bearer token comes back in the `x-user` response header of the first
  request and is reused for search / play-info / resource

Canonical string fields: `METHOD \n accept \n content-type \n bodyLength \n ts \n md5(body) \n path?sorted-query`.

Note: the signing key is **double base64 encoded** — decoding once gives 40 printable
characters, the real HMAC key is the second decode (30 bytes).

## Endpoints used

| Purpose | Endpoint |
|---|---|
| token bootstrap | `GET /wefeed-mobile-bff/tab-operating` |
| home rows | `GET /wefeed-mobile-bff/tab/ranking-list`, `POST /wefeed-mobile-bff/subject-api/list` |
| search | `POST /wefeed-mobile-bff/subject-api/search/v2` |
| detail + movie files | `GET /wefeed-mobile-bff/subject-api/get` |
| seasons | `GET /wefeed-mobile-bff/subject-api/season-info` |
| episodes | `GET /wefeed-mobile-bff/subject-api/resource` |
| streams + subtitles | `GET /wefeed-mobile-bff/subject-api/play-info`, `.../get-stream-captions` |

## Known limits

- Movie links come from `resourceDetectors.resolutionList` (real multi-quality files).
- Series episodes are served from `/subject-api/resource`. For some titles the API
  answers with a short promo clip unless the account is VIP — that is server side, not
  a bug in the extension.
- Link signatures expire; they are regenerated on every request, so keep the extension
  updated if MovieBox rotates its keys.
