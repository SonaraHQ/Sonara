package com.github.vexify4103.sonara.spotify;

import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.vexify4103.sonara.SonaraTools;
import com.github.vexify4103.sonara.mirror.DefaultMirroringAudioTrackResolver;
import com.github.vexify4103.sonara.mirror.MirroringAudioSourceManager;
import com.github.vexify4103.sonara.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifySourceManager extends MirroringAudioSourceManager
        implements HttpConfigurable, AudioSearchManager {

    public static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
    public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern
            .compile("mix:(?<seedType>album|artist|track):(?<seed>[a-zA-Z0-9-_]+)");
    public static final String SEARCH_PREFIX = "spsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "sprec:";
    public static final String PREVIEW_PREFIX = "spprev:";
    public static final long PREVIEW_LENGTH = 30000;
    public static final String CLIENT_API_BASE = "https://spclient.wg.spotify.com/";
    public static final String DEFAULT_ANONYMOUS_TOKEN_URL = "http://140.245.242.153:8082/api/token";
    public static final String SHARE_URL = "https://spotify.link/";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;
    private static final String DEFAULT_RESOLVER = "http://140.245.242.153:8081";
    // Playlist ids of Spotify-made playlists (editorial, Daily Mix, radios) start with this
    private static final String SPOTIFY_MADE_PLAYLIST_PREFIX = "37i9dQZ";
    public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM,
            AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
    private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    private final String countryCode;
    private String resolver = DEFAULT_RESOLVER;
    private String anonymousTokenUrl = DEFAULT_ANONYMOUS_TOKEN_URL;
    // Spotify-made playlists (Daily Mix, Top 50, radios...) are blocked on the public Web API.
    // When set, they are loaded from an hfy resolver (GET <url>/playlist/full/<id>) first.
    private String resolvePlaylistUrl;
    private String clientId;
    private String clientSecret;
    private volatile String anonymousAccessToken;
    private volatile long anonymousAccessTokenExpirationMs;
    private int playlistPageLimit = 6;
    private int albumPageLimit = 6;
    private boolean localFiles;
    private boolean resolveArtistsInSearch = true;

    public SpotifySourceManager(String[] providers, String countryCode, AudioPlayerManager audioPlayerManager) {
        this(countryCode, null, unused -> audioPlayerManager,
                new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String[] providers, String countryCode,
            Function<Void, AudioPlayerManager> audioPlayerManager) {
        this(countryCode, null, audioPlayerManager,
                new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String countryCode,
            AudioPlayerManager audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {
        this(countryCode, null, unused -> audioPlayerManager,
                mirroringAudioTrackResolver);
    }

    public SpotifySourceManager(String countryCode,
            Function<Void, AudioPlayerManager> audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {
        this(countryCode, null, audioPlayerManager, mirroringAudioTrackResolver);
    }

    public SpotifySourceManager(String countryCode,
            String apiUrl,
            Function<Void, AudioPlayerManager> audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {

        super(audioPlayerManager, mirroringAudioTrackResolver);

        if (countryCode == null || countryCode.isEmpty()) {
            countryCode = "US";
        }

        this.countryCode = countryCode;
        this.setApiUrl(apiUrl);

        this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(10000)
                .setSocketTimeout(15000)
                .build());
    }

    public void setPlaylistPageLimit(int playlistPageLimit) {
        this.playlistPageLimit = playlistPageLimit;
    }

    public void setAlbumPageLimit(int albumPageLimit) {
        this.albumPageLimit = albumPageLimit;
    }

    public void setLocalFiles(boolean localFiles) {
        this.localFiles = localFiles;
    }

    public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
        this.resolveArtistsInSearch = resolveArtistsInSearch;
    }

    public void setApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            this.resolver = DEFAULT_RESOLVER;
            return;
        }

        this.resolver = apiUrl;
    }

    public void setAnonymousTokenUrl(String anonymousTokenUrl) {
        if (anonymousTokenUrl == null || anonymousTokenUrl.isBlank()) {
            this.anonymousTokenUrl = DEFAULT_ANONYMOUS_TOKEN_URL;
            this.anonymousAccessToken = null;
            this.anonymousAccessTokenExpirationMs = 0;
            return;
        }

        this.anonymousTokenUrl = anonymousTokenUrl;
        this.anonymousAccessToken = null;
        this.anonymousAccessTokenExpirationMs = 0;
    }

    public void setResolvePlaylistUrl(String resolvePlaylistUrl, String clientId, String clientSecret) {
        if (resolvePlaylistUrl == null || resolvePlaylistUrl.isBlank()) {
            this.resolvePlaylistUrl = null;
        } else {
            var url = resolvePlaylistUrl.strip();
            this.resolvePlaylistUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @NotNull
    @Override
    public String getSourceName() {
        return "spotify";
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        var extendedAudioTrackInfo = super.decodeTrack(input);
        return new SpotifyAudioTrack(trackInfo,
                extendedAudioTrackInfo.albumName,
                extendedAudioTrackInfo.albumUrl,
                extendedAudioTrackInfo.artistUrl,
                extendedAudioTrackInfo.artistArtworkUrl,
                extendedAudioTrackInfo.previewUrl,
                extendedAudioTrackInfo.isPreview,
                this);
    }

    @Override
    @Nullable
    public AudioSearchResult loadSearch(@NotNull String query,
            @NotNull Set<AudioSearchResult.Type> types) {
        try {
            if (!query.startsWith(SEARCH_PREFIX)) {
                return null;
            }

            var q = URLEncoder.encode(
                    query.substring(SEARCH_PREFIX.length()),
                    StandardCharsets.UTF_8);

            var request = new HttpGet(
                    this.resolver + "/api/search?q=" + q);

            var json = SonaraTools.fetchResponseAsJson(
                    this.httpInterfaceManager.getInterface(),
                    request);

            var items = json.get("items");

            if (items == null || items.values().isEmpty()) {
                return null;
            }

            var tracks = new ArrayList<AudioTrack>();

            for (var item : items.values()) {

                var trackId = item.get("id").text();
                var artwork = item.get("artwork").safeText();
                var isrc = item.get("isrc").safeText();

                var info = new AudioTrackInfo(
                        item.get("title").text(),
                        parseArtists(item),
                        item.get("duration").asLong(0),
                        trackId,
                        false,
                        "https://open.spotify.com/track/" + trackId,
                        artwork,
                        isrc);

                tracks.add(new SpotifyAudioTrack(
                        info,
                        null,
                        null,
                        null,
                        artwork,
                        null,
                        false,
                        this));
            }

            return new BasicAudioSearchResult(
                    tracks,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());

        } catch (Exception e) {
            log.error("Search failed", e);
            return null;
        }
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        var identifier = reference.identifier;
        var preview = reference.identifier.startsWith(PREVIEW_PREFIX);
        return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
    }

    public AudioItem loadItem(String identifier, boolean preview) {
        try {

            if (identifier.startsWith(SEARCH_PREFIX)) {
                var query = identifier.substring(SEARCH_PREFIX.length());

                var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

                var request = new HttpGet(
                        this.resolver + "/api/search?q=" + encoded);

                var json = SonaraTools.fetchResponseAsJson(
                        this.httpInterfaceManager.getInterface(),
                        request);

                var items = json.get("items");

                if (items == null || items.values().isEmpty()) {
                    return AudioReference.NO_TRACK;
                }

                var item = items.index(0);

                var trackId = item.get("id").text();
                var artwork = item.get("artwork").safeText();
                var isrc = item.get("isrc").safeText();

                var info = new AudioTrackInfo(
                        item.get("title").text(),
                        parseArtists(item),
                        item.get("duration").asLong(0),
                        trackId,
                        false,
                        "https://open.spotify.com/track/" + trackId,
                        artwork,
                        isrc);

                return new SpotifyAudioTrack(
                        info,
                        null,
                        null,
                        null,
                        artwork,
                        null,
                        false,
                        this);
            }

            var matcher = URL_PATTERN.matcher(identifier);

            if (matcher.find()) {
                var type = matcher.group("type");
                var id = matcher.group("identifier");

                if ("artist".equals(type)) {
                    return resolveArtist(id);
                }

                if ("playlist".equals(type)) {
                    var playlist = resolveSpotifyMadePlaylist(id);
                    if (playlist != null) {
                        return playlist;
                    }
                }

                return resolveViaLeo(identifier, preview);
            }

            if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                return getRecommendations(
                        identifier.substring(RECOMMENDATIONS_PREFIX.length()), preview);
            }

            return null;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String parseArtists(JsonBrowser item) {
        return item.get("artists").isList()
                ? String.join(", ",
                        item.get("artists").values().stream()
                                .map(v -> v.text())
                                .collect(Collectors.toList()))
                : item.get("artists").text();
    }

    private AudioItem parsePlaylist(JsonBrowser json, String fallbackName) {

        if (json == null || json.get("items") == null || json.get("items").values().isEmpty()) {
            return null;
        }

        var tracks = new ArrayList<AudioTrack>();

        for (var item : json.get("items").values()) {

            var trackId = item.get("id").text();
            var artwork = item.get("artwork").safeText();
            var isrc = item.get("isrc").safeText();

            var info = new AudioTrackInfo(
                    item.get("title").text(),
                    parseArtists(item),
                    item.get("duration").asLong(0),
                    trackId,
                    false,
                    "https://open.spotify.com/track/" + trackId,
                    artwork,
                    isrc
            );

            tracks.add(new SpotifyAudioTrack(
                    info,
                    null,
                    null,
                    null,
                    artwork,
                    null,
                    false,
                    this));
        }

        if (tracks.size() == 1) {
            return tracks.get(0);
        }

        return new BasicAudioPlaylist(
                json.get("name").textOrDefault(fallbackName),
                tracks,
                null,
                false);
    }

    // Loads a Spotify-made playlist from resolvePlaylistUrl. Returns null when it is not one,
    // or when the resolver fails, so the default resolver is used instead.
    @Nullable
    private AudioItem resolveSpotifyMadePlaylist(String id) {
        if (this.resolvePlaylistUrl == null || !id.startsWith(SPOTIFY_MADE_PLAYLIST_PREFIX)) {
            return null;
        }

        // /full adds ISRCs (better mirroring) but needs the app credentials
        var hasCredentials = this.clientId != null && !this.clientId.isBlank()
                && this.clientSecret != null && !this.clientSecret.isBlank();
        var path = hasCredentials ? "/playlist/full/" : "/playlist/";
        var request = authorizedGet(this.resolvePlaylistUrl + path + URLEncoder.encode(id, StandardCharsets.UTF_8));
        if (hasCredentials) {
            // Headers, not query params, so the secret does not end up in logged URLs
            request.setHeader("X-Client-Id", this.clientId);
            request.setHeader("X-Client-Secret", this.clientSecret);
        }

        try (var httpInterface = this.httpInterfaceManager.getInterface()) {
            return parseResolvedPlaylist(SonaraTools.fetchResponseAsJson(httpInterface, request));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load Spotify-made playlist {} from resolvePlaylistUrl, using the default resolver: {}", id, e.getMessage());
            return null;
        }
    }

    // Reads a playlist in the Spotify Web API format, as returned by hfy
    @Nullable
    private AudioItem parseResolvedPlaylist(@Nullable JsonBrowser json) {
        if (json == null || json.isNull()) {
            return null;
        }

        var tracks = new ArrayList<AudioTrack>();
        for (var item : json.get("tracks").get("items").values()) {
            var track = item.get("track");
            var trackId = track.get("id").text();
            if (trackId == null || trackId.isBlank() || track.get("is_local").asBoolean(false)) {
                continue;
            }

            var artists = track.get("artists").values().stream()
                    .map(artist -> artist.get("name").text())
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.joining(", "));
            var album = track.get("album");
            var images = album.get("images").values();
            var artwork = images.isEmpty() ? null : images.get(0).get("url").text();
            var albumId = album.get("id").text();

            var info = new AudioTrackInfo(
                    track.get("name").text(),
                    artists,
                    track.get("duration_ms").asLong(0),
                    trackId,
                    false,
                    "https://open.spotify.com/track/" + trackId,
                    artwork,
                    track.get("external_ids").get("isrc").text());

            tracks.add(new SpotifyAudioTrack(
                    info,
                    album.get("name").text(),
                    albumId == null || albumId.isBlank() ? null : "https://open.spotify.com/album/" + albumId,
                    null,
                    artwork,
                    null,
                    false,
                    this));
        }

        if (tracks.isEmpty()) {
            return null;
        }

        return new BasicAudioPlaylist(json.get("name").textOrDefault("Spotify"), tracks, null, false);
    }

    // Credentials in a URL (https://user:pass@host/path) are sent as a Basic header up front
    // and removed from the URL, so they never show up in logged request URLs
    private static HttpGet authorizedGet(String url) {
        var uri = URI.create(url);
        var userInfo = uri.getUserInfo();
        if (userInfo == null) {
            return new HttpGet(uri);
        }

        var request = new HttpGet(uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                + (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        request.setHeader("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private AudioItem resolveViaLeo(String identifier, boolean preview) throws IOException {

        if (!identifier.contains("spotify.com")) {
            return null;
        }

        var encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8);

        var request = new HttpGet(
                this.resolver + "/api/resolve?url=" + encoded);

        var json = SonaraTools.fetchResponseAsJson(
                this.httpInterfaceManager.getInterface(),
                request);

        return parsePlaylist(json, "Spotify");
    }

    public AudioItem getRecommendations(String query, boolean preview) throws IOException {
        Matcher matcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
        if (matcher.find()) {
            var seedType = matcher.group("seedType");
            var seed = matcher.group("seed");

            // The resolver's direct recommendation endpoint supports track
            // URLs only. Album and artist mixes use Spotify's existing flow.
            if ("track".equals(seedType)) {
                var recommended = getResolverRecommendations(
                        "https://open.spotify.com/track/" + seed, 1);
                if (recommended != null && recommended != AudioReference.NO_TRACK) {
                    return recommended;
                }
            }

            // The resolver reports no items for some seeds; retain the former
            // inspired-by playlist behavior as the fallback in that case.
            return getInspiredByMix(seedType, seed, preview);
        }

        return getResolverRecommendations(query, 0);
    }

    private AudioItem getResolverRecommendations(String spotifyUrl, int limit) throws IOException {
        var encoded = URLEncoder.encode(spotifyUrl, StandardCharsets.UTF_8);
        var endpoint = this.resolver + "/api/recommendations?url=" + encoded;
        if (limit > 0) endpoint += "&limit=" + limit;

        var request = new HttpGet(endpoint);

        var json = SonaraTools.fetchResponseAsJson(
                this.httpInterfaceManager.getInterface(),
                request);

        var item = parsePlaylist(json, "Recommendations");
        return item == null ? AudioReference.NO_TRACK : item;
    }

    private AudioItem getInspiredByMix(String seedType, String seed, boolean preview) throws IOException {
        var accessToken = this.getAnonymousAccessToken();
        var request = new HttpGet(
                CLIENT_API_BASE + "inspiredby-mix/v2/seed_to_playlist/spotify:"
                        + seedType + ":" + seed + "?response-format=json");
        request.setHeader("Authorization", "Bearer " + accessToken);

        var json = SonaraTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
        var mediaItems = json.get("mediaItems");
        if (mediaItems != null && mediaItems.isList() && !mediaItems.values().isEmpty()) {
            var uri = mediaItems.index(0).get("uri").safeText();
            if (uri != null && !uri.isBlank()) {
                var parts = uri.split(":");
                if (parts.length >= 3) {
                    var item = resolveViaLeo("https://open.spotify.com/playlist/" + parts[2], preview);
                    return "track".equals(seedType) ? filterMixPlaylist(item, resolveSeedTrack(seed)) : item;
                }
            }
        }
        return AudioReference.NO_TRACK;
    }

    private AudioTrack resolveSeedTrack(String seed) throws IOException {
        var item = resolveViaLeo("https://open.spotify.com/track/" + seed, false);
        return item instanceof AudioTrack track ? track : null;
    }

    private AudioItem filterMixPlaylist(AudioItem item, AudioTrack seedTrack) {
        if (item instanceof AudioTrack track) {
            if (shouldSkipMixTrack(track, seedTrack, new LinkedHashSet<>())) {
                return AudioReference.NO_TRACK;
            }
            return track;
        }

        if (!(item instanceof AudioPlaylist playlist)) {
            return item;
        }

        var seenTitles = new LinkedHashSet<String>();
        var filteredTracks = playlist.getTracks().stream()
                .filter(track -> !shouldSkipMixTrack(track, seedTrack, seenTitles))
                .collect(Collectors.toCollection(ArrayList::new));

        if (filteredTracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        if (filteredTracks.size() == 1) {
            return filteredTracks.get(0);
        }

        return new BasicAudioPlaylist(
                playlist.getName(),
                filteredTracks,
                null,
                playlist.isSearchResult());
    }

    private boolean shouldSkipMixTrack(AudioTrack track, AudioTrack seedTrack, Set<String> seenTitles) {
        if (seedTrack != null) {
            if (seedTrack.getIdentifier().equals(track.getIdentifier())) {
                return true;
            }

            var seedTitle = normalizeMixTitle(seedTrack.getInfo().title);
            var trackTitle = normalizeMixTitle(track.getInfo().title);
            if (!seedTitle.isBlank() && seedTitle.equals(trackTitle)) {
                return true;
            }
        }

        var normalizedTitle = normalizeMixTitle(track.getInfo().title);
        if (!normalizedTitle.isBlank() && !seenTitles.add(normalizedTitle)) {
            return true;
        }

        return false;
    }

    private String normalizeMixTitle(String title) {
        if (title == null) {
            return "";
        }

        var normalized = title.toLowerCase()
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("\\[.*?\\]", " ")
                .replaceAll("\\s+-\\s+.*$", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return normalized;
    }

    private String getAnonymousAccessToken() throws IOException {
        var now = System.currentTimeMillis();
        if (this.anonymousAccessToken != null && now < this.anonymousAccessTokenExpirationMs - 5000) {
            return this.anonymousAccessToken;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            if (this.anonymousAccessToken != null && now < this.anonymousAccessTokenExpirationMs - 5000) {
                return this.anonymousAccessToken;
            }

            var tokenRequest = authorizedGet(this.anonymousTokenUrl);
            var tokenJson = SonaraTools.fetchResponseAsJson(
                    this.httpInterfaceManager.getInterface(),
                    tokenRequest);

            if (tokenJson == null) {
                throw new IllegalStateException("Spotify anonymous token endpoint returned no data.");
            }

            var accessToken = findTextValue(tokenJson, "accessToken");
            var expirationTimestampMs = findLongValue(tokenJson, "accessTokenExpirationTimestampMs");
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Spotify anonymous token endpoint did not return accessToken.");
            }

            this.anonymousAccessToken = accessToken;
            this.anonymousAccessTokenExpirationMs = expirationTimestampMs > 0 ? expirationTimestampMs : now + 300000;
            return this.anonymousAccessToken;
        }
    }

    private String findTextValue(JsonBrowser json, String key) {
        if (json == null || json.isNull()) {
            return null;
        }

        var directValue = json.get(key);
        if (directValue != null && !directValue.isNull()) {
            var text = directValue.safeText();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }

        if (json.isList()) {
            for (var value : json.values()) {
                var text = findTextValue(value, key);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            return null;
        }

        for (var value : json.values()) {
            var text = findTextValue(value, key);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }

        return null;
    }

    private long findLongValue(JsonBrowser json, String key) {
        if (json == null || json.isNull()) {
            return 0;
        }

        var directValue = json.get(key);
        if (directValue != null && !directValue.isNull()) {
            var value = directValue.asLong(0);
            if (value > 0) {
                return value;
            }
        }

        if (json.isList()) {
            for (var value : json.values()) {
                var nestedValue = findLongValue(value, key);
                if (nestedValue > 0) {
                    return nestedValue;
                }
            }
            return 0;
        }

        for (var value : json.values()) {
            var nestedValue = findLongValue(value, key);
            if (nestedValue > 0) {
                return nestedValue;
            }
        }

        return 0;
    }

    private AudioItem resolveArtist(String id) throws IOException {

        var request = new HttpGet(
                this.resolver + "/api/artist-top-tracks?id=" + id);

        var json = SonaraTools.fetchResponseAsJson(
                this.httpInterfaceManager.getInterface(),
                request);

        var artistName = json.get("name").safeText();

        if (artistName != null) {
            json.put("name", artistName + " - Top Tracks");
        }

        return parsePlaylist(json, "Artist Top Tracks");
    }

    @Override
    public void shutdown() {
        try {
            this.httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        this.httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        this.httpInterfaceManager.configureBuilder(configurator);
    }
}
