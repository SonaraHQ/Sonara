# Sonara

[![JitPack](https://jitpack.io/v/SonaraHQ/Sonara.svg)](https://jitpack.io/#SonaraHQ/Sonara)

Sonara is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your own providers (for example YouTube search). Some sources also support direct playback.

Sonara is a fork of [PulseLink](https://github.com/ItzRandom23/PulseLink), which is based on [LavaSrc](https://github.com/topi314/LavaSrc). See [Credits](#credits) for what changed.

## Quick Start

1. Add Sonara to your Lavalink `application.yml` with JitPack. Replace `VERSION` with the latest release shown on the badge above (for example `1.0.0`):

```yaml
lavalink:
  plugins:
    - dependency: "com.github.SonaraHQ:Sonara:VERSION"
      repository: "https://jitpack.io"
```

2. Configure Sonara under `plugins.sonara`. Use [`application.example.yml`](https://github.com/SonaraHQ/Sonara/blob/main/application.example.yml) as the full configuration reference.
3. For Spotify, set up your own [Sonara-API](#spotify) server.
4. Start Lavalink and check the log for `Loading Sonara plugin...` and a `Registering ... audio source manager...` line for every source you enabled.

> [!IMPORTANT]
> Do not load Sonara together with LavaSrc or PulseLink. They register the same sources and Lavalink fails to start. When switching, also delete their old jars from Lavalink's `plugins/` folder, since Lavalink loads every jar in that folder.

## Configuration

See [`application.example.yml`](https://github.com/SonaraHQ/Sonara/blob/main/application.example.yml) for the full example configuration. All settings live under `plugins.sonara`; settings under `plugins.lavasrc` or `plugins.pulselink` are ignored.

### Spotify

Spotify's public Web API no longer serves everything a music bot needs. Spotify-made playlists (Daily Mix, Discover Weekly, Top 50, radios), recommendations and lyrics are blocked for newer apps. Sonara therefore uses two backends:

| Feature | Backend | Needs |
|---------|---------|-------|
| Tracks, albums, artist top tracks, `spsearch:` | Spotify Web API | `clientId` / `clientSecret` |
| Playlists, including Spotify-made ones | Sonara-API | `resolvePlaylistUrl` (user playlists fall back to the Web API without it) |
| `sprec:` recommendations | Sonara-API | `resolvePlaylistUrl` |
| Album and artist mixes (`sprec:mix:album:` / `sprec:mix:artist:`) | Spotify's inspired-by mix API | `anonymousTokenUrl` |
| Lyrics (`lyrics-sources.spotify`) | Sonara-API | `resolvePlaylistUrl`, and `SPOTIFY_SP_DC` on the Sonara-API server |

#### 1. Create a Spotify app

Create an app in the [Spotify developer dashboard](https://developer.spotify.com/dashboard) and copy its client ID and secret.

#### 2. Host Sonara-API

[Sonara-API](https://github.com/SonaraHQ/Sonara-API) is a small Node.js server you host yourself. There is no public instance. It loads Spotify through the same API the Spotify web player uses, with a token it gets from a headless Chromium.

```bash
git clone https://github.com/SonaraHQ/Sonara-API.git
cd Sonara-API
pnpm install
pnpm install:browser
cp .env.example .env
pnpm build
pnpm start:prod
```

It needs Node.js 22.18 or newer. In `.env`:

```env
PORT=1500
# Optional: enables Spotify lyrics. The sp_dc cookie of a Spotify account
# (open.spotify.com while logged in, browser dev tools, Application, Cookies).
# Use a separate account, not your personal one.
SPOTIFY_SP_DC=
```

Run a single instance, since its token cache lives in memory. Sonara-API has no login of its own, so do not expose its port to the internet: run it on the same machine or private network as Lavalink. See the [Sonara-API README](https://github.com/SonaraHQ/Sonara-API#readme) for all settings and routes.

#### 3. Point Sonara at it

```yaml
plugins:
  sonara:
    sources:
      spotify: true
    spotify:
      clientId: "your client id"
      clientSecret: "your client secret"
      # Base URL of your Sonara-API. Sonara adds /playlist, /recommendations and /lyrics itself.
      resolvePlaylistUrl: "http://your-sonara-api-host:1500"
      anonymousTokenUrl: "http://your-sonara-api-host:1500/spotifytoken"
      countryCode: "US"
```

- With `clientId` / `clientSecret` set, Sonara uses Sonara-API's `/full` playlist and recommendation routes, which include ISRCs, so tracks mirror more reliably. The credentials are sent to Sonara-API as headers, never in the URL.
- If you put Sonara-API behind a reverse proxy that asks for a login (Basic authentication), you can add the login to both URLs (`https://user:password@host`). Sonara sends it as an Authorization header and removes it from the request URL, so it does not show up in logs.
- Without `clientId` / `clientSecret`, the Web API calls use the anonymous token from `anonymousTokenUrl` instead.

### Lyrics

Lavalink's lyrics endpoint comes from the [LavaLyrics plugin](https://github.com/topi314/LavaLyrics). Install it next to Sonara, then turn on the lyrics sources you want:

```yaml
plugins:
  lavalyrics:
    # The order in which the sources are asked
    sources:
      - spotify
      - deezer
      - lrclib
      - genius
      - youtube
  sonara:
    lyrics-sources:
      spotify: true
      deezer: true
      lrcLib: true
      genius: true
      youtube: true
```

| Source | Name in `lavalyrics.sources` | Synced | Needs |
|--------|------------------------------|--------|-------|
| Spotify | `spotify` | yes, for most songs | Sonara-API with `SPOTIFY_SP_DC` (see [Spotify](#spotify)). Tracks from other sources are matched by ISRC, which needs `clientId` / `clientSecret` |
| Deezer | `deezer` | yes, for many songs | `deezer.arl` |
| LRCLIB | `lrclib` | yes, when available | nothing |
| Genius | `genius` | no, plain text | nothing |
| YouTube Music | `youtube` | no | `sources.youtube: true` |
| Yandex Music | `yandexmusic` | depends | `yandexmusic.accessToken` |
| VK Music | `vkmusic` | depends | `vkmusic.userToken` |

Sonara bundles a LavaLyrics build in which one failing source no longer fails the whole lookup: the next source is asked instead, and a song without lyrics returns "no lyrics" instead of an error. LRCLIB and Genius search by the track's title and artist.

## Providers and Mirroring

Sonara mirrors playback for services that do not stream directly.

When a track is requested, Sonara builds a provider query using the track ISRC if available, then falls back to a title/artist query. Configure the fallback order with `plugins.sonara.providers`.

Playback modes:
- Mirror: metadata only, audio comes from your configured providers.
- Direct: Sonara streams audio directly.

| Source | Access | Notes |
|--------|--------|-------|
| Spotify | Mirror | Requires `clientId` / `clientSecret` and your own [Sonara-API](#spotify). |
| Amazon Music | Mirror | Requires `apiUrl`, a compatible Amazon Music API server. There is no default. |
| Apple Music | Mirror | `mediaAPIToken` is optional. |
| Tidal | Mirror / Direct | Uses the built-in default token; direct streaming also needs hifi-api. |
| Qobuz | Mirror | `userOauthToken` is required. |
| Deezer | Direct | Requires `arl` and `masterDecryptionKey`. |
| Yandex Music | Direct | Requires `accessToken`. |
| VK Music | Direct | Requires `userToken`. |
| JioSaavn | Direct | Uses built-in decryption defaults; `decryption` can be overridden in config. |
| Audiomack | Direct | Uses built-in web credentials; `consumerKey` and `consumerSecret` can be overridden. |
| Gaana | Direct | No credentials required. |
| SoundCloud | Direct / Mirror fallback | No credentials required. Supports tracks, sets/playlists, user pages, `m.soundcloud.com`, `on.soundcloud.com`, and `snd.sc` links. Full SoundCloud results can be selected as mirrors; preview-only results proceed to the next provider. Lavalink also has a built-in SoundCloud source; enable only one of them. |
| Pandora | Mirror | Uses the built-in remote token provider; `remoteTokenUrl`, `csrfToken`, or `authToken` can override it. |
| yt-dlp | Direct | Requires `yt-dlp` installed. |
| FloweryTTS | Direct | No credentials required. |
| YouTube | Search / Lyrics | Requires the new YouTube source plugin for playback. |

Credentials and external requirements:
- No credentials required: Pandora, Gaana, SoundCloud, FloweryTTS
- Self-hosted servers: Spotify needs Sonara-API, Amazon Music needs `apiUrl`
- Optional overrides: Apple Music `mediaAPIToken`, Tidal `token` or `appId`/`appSecret`, Audiomack `consumerKey` / `consumerSecret`, JioSaavn `decryption`, Pandora `remoteTokenUrl` / `csrfToken` / `authToken`
- Required credentials: Spotify `clientId` / `clientSecret`, Deezer `arl` and `masterDecryptionKey`, Yandex Music `accessToken`, VK Music `userToken`, Qobuz `userOauthToken`
- Other required setup: `yt-dlp` installed for yt-dlp, and the new YouTube source plugin for YouTube playback

Supported search prefixes:
- Spotify: `spsearch:query` (up to 10 results)
- Spotify recommendations: `sprec:mix:track:id` asks Sonara-API for one direct next-track recommendation, then falls back to Spotify's inspired-by mix; `sprec:mix:album:id` and `sprec:mix:artist:id` use the inspired-by mix; any other `sprec:` query is a Spotify track URL, URI or id sent to Sonara-API's recommendations.
- Amazon Music: `amzsearch:query`
- Apple Music: `amsearch:query`
- Deezer: `dzsearch:query`
- Yandex Music: `ymsearch:query`
- VK Music: `vksearch:query`
- Tidal: `tdsearch:query`
- Qobuz: `qbsearch:query`
- JioSaavn: `jssearch:query`
- Audiomack: `admsearch:query`
- Gaana: `gnsearch:query`
- SoundCloud: `scsearch:query`
- Pandora: `pdsearch:query`
- yt-dlp: `ytsearch:query`
- YouTube Music autocomplete/search: `ytmsearch:query`

SoundCloud fallback notes:
- If SoundCloud only exposes a 30-second preview, Sonara resolves a mirror during load and uses the mirror track duration for the queued SoundCloud track.
- `scsearch:` may be included in mirror providers: playable full SoundCloud search results are used, while preview-only results are skipped without recursive SoundCloud searching.

## Troubleshooting

- **A source is missing or tracks fail with "missing source manager":** check that your settings are under `plugins.sonara` and that the log shows `Registering <source> audio source manager...`.
- **Lavalink fails to start with a "conflicting bean definition" error:** another LavaSrc, PulseLink or older Sonara jar is still in `plugins/`. Delete it.
- **Spotify playlists do not load:** check that `resolvePlaylistUrl` points at the base URL of your Sonara-API and that Sonara-API answers. A Spotify-made playlist logs a warning when it cannot be loaded without Sonara-API.
- **Spotify lyrics are missing:** Sonara-API needs `SPOTIFY_SP_DC`; its `/lyrics` route answers 503 without it.
- **Mirrored tracks do not resolve:** verify your `providers` order and keep a `%QUERY%` fallback after `%ISRC%`.
- **YouTube:** YouTube playback needs the new YouTube source plugin.
- The runtime `PATCH /v4/sonara/config` endpoint only updates mutable fields present in the payload; null fields are ignored.
- Local Gradle builds and JitPack builds require Java 17.

## Releases

Every Git tag is a release on JitPack: `com.github.SonaraHQ:Sonara:<tag>`. The `versionStr` in `build.gradle` matches the tag, and that is the version Lavalink shows in its log.

## Contributing

- Prefer small, focused changes.
- Add deterministic unit tests for resolver logic, registration behavior, and config/status safety when you touch those areas.
- Update this README when config, release expectations, or operational behavior changes.

## Security

Please report security issues privately through GitHub Security Advisories for this repository rather than opening a public issue.

## Credits

Sonara is a fork of [PulseLink](https://github.com/ItzRandom23/PulseLink) by ItzRandom23, licensed under the Apache License 2.0.
PulseLink is based on [LavaSrc](https://github.com/topi314/LavaSrc) by topi314 and community contributors.

Changes in Sonara compared to PulseLink:
- Renamed to Sonara (packages `com.github.vexify4103.sonara`, plugin `sonara-plugin`, config `plugins.sonara`, runtime endpoint `PATCH /v4/sonara/config`).
- Spotify no longer depends on PulseLink's resolver server: tracks, albums, artists and search use the Spotify Web API (`clientId` / `clientSecret`), and playlists, recommendations and lyrics use your own [Sonara-API](https://github.com/SonaraHQ/Sonara-API) server (`resolvePlaylistUrl`). The `spotify.apiUrl` setting is removed.
- No built-in third-party server addresses: `anonymousTokenUrl` and Amazon Music's new `apiUrl` have no defaults.
- Credentials in `resolvePlaylistUrl` and `anonymousTokenUrl` are sent as a Basic Authorization header instead of in the request URL.
- New lyrics sources: Genius (`lyrics-sources.genius`) and Spotify through Sonara-API (`lyrics-sources.spotify`).
- Deezer lyrics work again: the request now sends the session cookies its token belongs to, instead of failing with "Invalid CSRF token".
- LRCLIB synced lyrics without a space after the timestamp no longer fail the whole lyrics request.
- Bundles a LavaLyrics build where one failing lyrics source no longer fails the whole lookup.
