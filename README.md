# Sonara

Sonara is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your own providers (for example YouTube search). Some sources also support direct playback.

Sonara is a fork of [PulseLink](https://github.com/ItzRandom23/PulseLink), which is based on [LavaSrc](https://github.com/topi314/LavaSrc). See [Credits](#credits) for what changed.

## Quick Start

1. Add Sonara to your Lavalink `application.yml` with JitPack:

```yaml
lavalink:
  plugins:
    - dependency: "com.github.SonaraHQ:Sonara:1.0.0"
      repository: "https://jitpack.io"
```

2. Use [`application.example.yml`](https://github.com/SonaraHQ/Sonara/blob/main/application.example.yml) as the full configuration reference.
3. Start Lavalink.

## Configuration

See [`application.example.yml`](https://github.com/SonaraHQ/Sonara/blob/main/application.example.yml) for the current full example configuration.

### Lyrics

Turn on the lyrics sources you want:

```yaml
plugins:
  sonara:
    lyrics-sources:
      lrcLib: true
      genius: true
      spotify: true
      deezer: true
      youtube: true
```

| Source | Synced | Needs |
|--------|--------|-------|
| `lrcLib` | yes, when available | nothing |
| `genius` | no, plain text | nothing |
| `spotify` | yes, for most songs | `spotify.resolvePlaylistUrl` pointing at a Sonara-API server with `SPOTIFY_SP_DC` set (Spotify only serves lyrics to logged-in users). Tracks from other sources are matched by ISRC, which needs `clientId`/`clientSecret` |
| `deezer` | yes, for many songs | `deezer.arl` |
| `youtube` | no | `sources.youtube: true` |
| `yandexmusic` / `vkmusic` | depends | their access tokens |

LRCLIB and Genius search by the loaded track's title and artist. The order in which the sources are asked is set in LavaLyrics' `plugins.lavalyrics.sources` (source names: `lrclib`, `genius`, `spotify`, `deezer`, `youtube`, `yandexmusic`, `vkmusic`).

### Spotify

Sonara loads Spotify in two ways:

- **Spotify Web API** for tracks, albums, artist top tracks and `spsearch:`. Set `clientId` and `clientSecret` from the [Spotify developer dashboard](https://developer.spotify.com/dashboard).
- **[Sonara-API](https://github.com/SonaraHQ/Sonara-API)** for playlists and recommendations. Sonara-API is a small self-hosted Spotify resolver. It uses Spotify's web player API, so it also serves Spotify-made playlists (Daily Mix, Discover Weekly, Top 50, radios) and recommendations, which the Web API blocks for newer apps. It also provides the anonymous token endpoint (`/spotifytoken`).

There is no public Sonara-API server: host your own (see the Sonara-API README) and point Sonara at it.

```yaml
plugins:
  sonara:
    spotify:
      clientId: "your client id"
      clientSecret: "your client secret"
      resolvePlaylistUrl: "https://user:password@your-sonara-api-host"
      anonymousTokenUrl: "https://user:password@your-sonara-api-host/spotifytoken"
```

- Without `resolvePlaylistUrl`, user playlists still load through the Web API, but Spotify-made playlists and `sprec:` recommendations do not.
- With `clientId`/`clientSecret` set, Sonara asks Sonara-API for the `/full` playlist and recommendation routes, which add ISRCs for better mirroring. The credentials are sent as headers, never in the URL.
- Credentials in a URL (`https://user:password@host`) are sent as a Basic Authorization header and removed from the request URL, so they do not show up in logs.

The Lavalink lyrics endpoint is provided by the [LavaLyrics plugin](https://github.com/topi314/LavaLyrics); install it alongside Sonara when you need `GET /v4/sessions/{sessionId}/players/{guildId}/track/lyrics`.

## Providers and Mirroring

Sonara mirrors playback for services that do not stream directly.

When a track is requested, Sonara builds a provider query using the track ISRC if available, then falls back to a title/artist query. Configure the fallback order with `plugins.sonara.providers`.

Playback modes:
- Mirror: metadata only, audio comes from your configured providers.
- Direct: Sonara streams audio directly.

| Source | Access | Notes |
|--------|--------|-------|
| Spotify | Mirror | Requires `clientId`/`clientSecret` (or `anonymousTokenUrl`). `resolvePlaylistUrl` points at your [Sonara-API](https://github.com/SonaraHQ/Sonara-API) resolver for playlists and recommendations. See [Spotify](#spotify). |
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
| SoundCloud | Direct / Mirror fallback | No credentials required. Supports tracks, sets/playlists, user pages, `m.soundcloud.com`, `on.soundcloud.com`, and `snd.sc` links. Full SoundCloud results can be selected as mirrors; preview-only results proceed to the next provider. |
| Pandora | Mirror | Uses the built-in remote token provider; `remoteTokenUrl`, `csrfToken`, or `authToken` can override it. |
| yt-dlp | Direct | Requires `yt-dlp` installed. |
| FloweryTTS | Direct | No credentials required. |
| YouTube | Search / Lyrics | Requires the new YouTube source plugin. |

Credentials and external requirements:
- No credentials required: Pandora, Gaana, SoundCloud, FloweryTTS
- Spotify needs `clientId`/`clientSecret`, plus your own Sonara-API server (`resolvePlaylistUrl`) for Spotify-made playlists and recommendations. Amazon Music needs `apiUrl`.
- Spotify track-mix recommendations (`sprec:mix:track:id`) first ask Sonara-API for one direct recommendation. Album and artist mixes, and track mixes with no direct result, use Spotify's inspired-by mix API, which needs `plugins.sonara.spotify.anonymousTokenUrl` (for example Sonara-API's `/spotifytoken`).
- Optional overrides: Apple Music `mediaAPIToken`, Tidal `token` or `appId`/`appSecret`, Audiomack `consumerKey` / `consumerSecret`, JioSaavn `decryption`, Pandora `remoteTokenUrl` / `csrfToken` / `authToken`
- Required credentials: Deezer `arl` and `masterDecryptionKey`, Yandex Music `accessToken`, VK Music `userToken`, Qobuz `userOauthToken`
- Other required setup: `yt-dlp` installed for yt-dlp, and the new YouTube source plugin for YouTube search / lyrics

Supported search prefixes:
- Spotify: `spsearch:query`
- Spotify mix recommendations: `sprec:mix:track:id` requests one direct next-track recommendation before fallback; `sprec:mix:album:id` and `sprec:mix:artist:id` use Spotify's inspired-by mix flow.
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

Common checks:
- If mirrored tracks do not resolve, verify your `providers` order and keep a `%QUERY%` fallback after `%ISRC%`.
- If YouTube search or lyrics is enabled, the new YouTube source plugin must also be available.
- The runtime PATCH endpoint only updates mutable fields present in the payload; null fields are ignored.
- Local Gradle builds and JitPack builds require Java 17.

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
- Renamed to Sonara (packages `com.github.vexify4103.sonara`, plugin `sonara-plugin`, config `plugins.sonara`).
- LRCLIB synced lyrics without a space after the timestamp no longer fail the whole lyrics request.
- Bundles a LavaLyrics build where one failing lyrics source no longer fails the whole lookup.
- Spotify no longer depends on PulseLink's resolver server: tracks, albums, artists and search use the Spotify Web API (`clientId`/`clientSecret`), and playlists and recommendations use your own [Sonara-API](https://github.com/SonaraHQ/Sonara-API) server (`resolvePlaylistUrl`). The `spotify.apiUrl` setting is removed.
- No built-in third-party server addresses: `anonymousTokenUrl` and Amazon Music's new `apiUrl` have no defaults.
- Credentials in `resolvePlaylistUrl` and `anonymousTokenUrl` are sent as a Basic Authorization header instead of in the request URL.
- New lyrics sources: Genius (`lyrics-sources.genius`) and Spotify through Sonara-API (`lyrics-sources.spotify`).
- Deezer lyrics work again: the request now sends the session cookies its token belongs to, instead of failing with "Invalid CSRF token".
