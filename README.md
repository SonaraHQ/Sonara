# Sonara

Sonara is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your own providers (for example YouTube search). Some sources also support direct playback.

Sonara is a fork of [PulseLink](https://github.com/ItzRandom23/PulseLink), which is based on [LavaSrc](https://github.com/topi314/LavaSrc). See [Credits](#credits) for what changed.

## Quick Start

1. Add Sonara to your Lavalink `application.yml` with JitPack:

```yaml
plugins:
  - dependency: "com.github.Vexify4103:Sonara:v1.0.0"
    repository: "https://jitpack.io"
    snapshot: false
```

2. Use [`application.example.yml`](https://github.com/Vexify4103/Sonara/blob/main/application.example.yml) as the full configuration reference.
3. Start Lavalink.

## Configuration

See [`application.example.yml`](https://github.com/Vexify4103/Sonara/blob/main/application.example.yml) for the current full example configuration.

### Lyrics

Enable LRCLIB lyrics with:

```yaml
plugins:
  sonara:
    lyrics-sources:
      lrcLib: true
```

LRCLIB searches by the loaded track's title and artist and returns plain lyrics, synchronized lyrics, or both when available.

The Lavalink lyrics endpoint is provided by the [LavaLyrics plugin](https://github.com/topi314/LavaLyrics); install it alongside Sonara when you need `GET /v4/sessions/{sessionId}/players/{guildId}/track/lyrics`.

## Providers and Mirroring

Sonara mirrors playback for services that do not stream directly.

When a track is requested, Sonara builds a provider query using the track ISRC if available, then falls back to a title/artist query. Configure the fallback order with `plugins.sonara.providers`.

Playback modes:
- Mirror: metadata only, audio comes from your configured providers.
- Direct: Sonara streams audio directly.

| Source | Access | Notes |
|--------|--------|-------|
| Spotify | Mirror | `apiUrl` and `anonymousTokenUrl` are optional. `resolvePlaylistUrl` loads Spotify-made playlists (Daily Mix, Top 50, radios) from an [hfy](https://github.com/Yanishamburger/hfy) resolver; `clientId`/`clientSecret` add ISRCs. |
| Amazon Music | Mirror | No credentials required. |
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
- No credentials required: Spotify, Amazon Music, Pandora, Gaana, SoundCloud, FloweryTTS
- Spotify track-mix recommendations first request one direct recommendation from the configured Spotify resolver. Album and artist mixes, and track mixes with no direct result, use Spotify's inspired-by mix API, which uses the anonymous token endpoint. By default that token endpoint is `http://140.245.242.153:8082/api/token`; override it with `plugins.sonara.spotify.anonymousTokenUrl`.
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
- `spotify.resolvePlaylistUrl` loads Spotify-made playlists from an hfy resolver.
- Credentials in `resolvePlaylistUrl` and `anonymousTokenUrl` are sent as a Basic Authorization header instead of in the request URL.
