package com.github.vexify4103.sonara.spotify;

import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.vexify4103.sonara.ExtendedAudioPlaylist;
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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spotify source.
 * <ul>
 *   <li>Tracks, albums, artist top tracks and search use Spotify's Web API with the app credentials
 *   ({@code clientId}/{@code clientSecret}), or the anonymous token when no credentials are set.</li>
 *   <li>Playlists and recommendations use a Sonara-API resolver ({@code resolvePlaylistUrl}) when it is set.
 *   It also serves Spotify-made playlists (Daily Mix, Top 50, radios), which the Web API blocks.
 *   Without a resolver, user playlists still load through the Web API.</li>
 * </ul>
 */
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
    public static final String API_BASE = "https://api.spotify.com/v1/";
    public static final String CLIENT_API_BASE = "https://spclient.wg.spotify.com/";
    public static final String ACCOUNTS_TOKEN_URL = "https://accounts.spotify.com/api/token";
    public static final String SHARE_URL = "https://spotify.link/";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;
    // The Web API's GET /tracks accepts at most 50 ids
    private static final int TRACKS_BATCH_SIZE = 50;
    private static final int SEARCH_LIMIT = 10;
    // Playlist ids of Spotify-made playlists (editorial, Daily Mix, radios) start with this
    private static final String SPOTIFY_MADE_PLAYLIST_PREFIX = "37i9dQZ";
    public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM,
            AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
    private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    private final String countryCode;
    private String clientId;
    private String clientSecret;
    private String anonymousTokenUrl;
    private String resolvePlaylistUrl;
    private volatile String clientAccessToken;
    private volatile long clientAccessTokenExpirationMs;
    private volatile String anonymousAccessToken;
    private volatile long anonymousAccessTokenExpirationMs;
    private int playlistPageLimit = 6;
    private int albumPageLimit = 6;
    private boolean localFiles;
    private boolean resolveArtistsInSearch = true;

    public SpotifySourceManager(String[] providers, String countryCode, AudioPlayerManager audioPlayerManager) {
        this(countryCode, unused -> audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String[] providers, String countryCode,
            Function<Void, AudioPlayerManager> audioPlayerManager) {
        this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String countryCode,
            AudioPlayerManager audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {
        this(countryCode, unused -> audioPlayerManager, mirroringAudioTrackResolver);
    }

    public SpotifySourceManager(String countryCode,
            Function<Void, AudioPlayerManager> audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {

        super(audioPlayerManager, mirroringAudioTrackResolver);

        if (countryCode == null || countryCode.isEmpty()) {
            countryCode = "US";
        }

        this.countryCode = countryCode;

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

    public void setClientCredentials(String clientId, String clientSecret) {
        this.clientId = blankToNull(clientId);
        this.clientSecret = blankToNull(clientSecret);
        this.clientAccessToken = null;
        this.clientAccessTokenExpirationMs = 0;
    }

    public void setAnonymousTokenUrl(String anonymousTokenUrl) {
        this.anonymousTokenUrl = blankToNull(anonymousTokenUrl);
        this.anonymousAccessToken = null;
        this.anonymousAccessTokenExpirationMs = 0;
    }

    public void setResolvePlaylistUrl(String resolvePlaylistUrl) {
        var url = blankToNull(resolvePlaylistUrl);
        this.resolvePlaylistUrl = url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
    public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
        if (!query.startsWith(SEARCH_PREFIX)) {
            return null;
        }

        var requested = types.isEmpty() ? SEARCH_TYPES : types;
        var typeParam = requested.stream()
                .filter(SEARCH_TYPES::contains)
                .map(type -> type.name().toLowerCase())
                .collect(Collectors.joining(","));
        if (typeParam.isEmpty()) {
            return null;
        }

        try {
            var json = this.apiGet("search?q=" + encode(query.substring(SEARCH_PREFIX.length()))
                    + "&type=" + typeParam + "&limit=" + SEARCH_LIMIT + "&market=" + encode(this.countryCode));
            if (json == null) {
                return null;
            }

            var albums = new ArrayList<AudioPlaylist>();
            for (var album : json.get("albums").get("items").values()) {
                if (!album.isNull()) {
                    albums.add(new SpotifyAudioPlaylist(album.get("name").safeText(), List.of(), ExtendedAudioPlaylist.Type.ALBUM,
                            album.get("external_urls").get("spotify").text(), firstImage(album), joinArtists(album.get("artists")),
                            (int) album.get("total_tracks").asLong(0)));
                }
            }

            var artists = new ArrayList<AudioPlaylist>();
            for (var artist : json.get("artists").get("items").values()) {
                if (!artist.isNull()) {
                    artists.add(new SpotifyAudioPlaylist(artist.get("name").safeText() + " - Top Tracks", List.of(), ExtendedAudioPlaylist.Type.ARTIST,
                            artist.get("external_urls").get("spotify").text(), firstImage(artist), artist.get("name").text(), null));
                }
            }

            var playlists = new ArrayList<AudioPlaylist>();
            for (var playlist : json.get("playlists").get("items").values()) {
                if (!playlist.isNull()) {
                    playlists.add(new SpotifyAudioPlaylist(playlist.get("name").safeText(), List.of(), ExtendedAudioPlaylist.Type.PLAYLIST,
                            playlist.get("external_urls").get("spotify").text(), firstImage(playlist), playlist.get("owner").get("display_name").text(),
                            (int) playlist.get("tracks").get("total").asLong(0)));
                }
            }

            return new BasicAudioSearchResult(this.parseTracks(json.get("tracks").get("items").values(), false),
                    albums, artists, playlists, new ArrayList<>());
        } catch (IOException | RuntimeException e) {
            log.error("Spotify search failed", e);
            return null;
        }
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        var identifier = reference.identifier;
        var preview = identifier.startsWith(PREVIEW_PREFIX);
        return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
    }

    public AudioItem loadItem(String identifier, boolean preview) {
        try {
            if (identifier.startsWith(SEARCH_PREFIX)) {
                return this.getSearch(identifier.substring(SEARCH_PREFIX.length()), preview);
            }

            if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                return this.getRecommendations(identifier.substring(RECOMMENDATIONS_PREFIX.length()), preview);
            }

            var matcher = URL_PATTERN.matcher(identifier);
            if (!matcher.find()) {
                return null;
            }

            var id = matcher.group("identifier");
            switch (matcher.group("type")) {
                case "track":
                    return this.getTrack(id, preview);
                case "album":
                    return this.getAlbum(id, preview);
                case "playlist":
                    return this.getPlaylist(id, preview);
                case "artist":
                    return this.getArtist(id, preview);
                default:
                    return null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public AudioItem getSearch(String query, boolean preview) throws IOException {
        var json = this.apiGet("search?q=" + encode(query) + "&type=track&limit=" + SEARCH_LIMIT + "&market=" + encode(this.countryCode));
        var tracks = json == null ? List.<AudioTrack>of() : this.parseTracks(json.get("tracks").get("items").values(), preview);
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
    }

    public AudioItem getTrack(String id, boolean preview) throws IOException {
        var json = this.apiGet("tracks/" + encode(id) + "?market=" + encode(this.countryCode));
        var track = json == null ? null : this.parseTrack(json, preview);
        return track == null ? AudioReference.NO_TRACK : track;
    }

    public AudioItem getAlbum(String id, boolean preview) throws IOException {
        var json = this.apiGet("albums/" + encode(id) + "?market=" + encode(this.countryCode));
        if (json == null || json.get("id").isNull()) {
            return AudioReference.NO_TRACK;
        }

        // Album track objects have no ISRC, so the full tracks are fetched by id afterwards
        var ids = new ArrayList<String>();
        var page = json.get("tracks");
        var offset = 0;
        var pages = 0;
        while (page != null) {
            for (var item : page.get("items").values()) {
                var trackId = item.get("id").text();
                if (trackId != null && !trackId.isBlank()) {
                    ids.add(trackId);
                }
            }
            offset += ALBUM_MAX_PAGE_ITEMS;
            if (page.get("next").text() == null || ++pages >= Math.max(this.albumPageLimit, 1)) {
                break;
            }
            page = this.apiGet("albums/" + encode(id) + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset
                    + "&market=" + encode(this.countryCode));
        }

        var tracks = this.getTracksById(ids, preview);
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist(json.get("name").safeText(), tracks, ExtendedAudioPlaylist.Type.ALBUM,
                json.get("external_urls").get("spotify").text(), firstImage(json), joinArtists(json.get("artists")),
                (int) json.get("total_tracks").asLong(tracks.size()));
    }

    public AudioItem getPlaylist(String id, boolean preview) throws IOException {
        if (this.resolvePlaylistUrl != null) {
            var playlist = this.getPlaylistFromResolver(id, preview);
            if (playlist != null) {
                return playlist;
            }
        }
        return this.getPlaylistFromWebApi(id, preview);
    }

    public AudioItem getArtist(String id, boolean preview) throws IOException {
        var json = this.apiGet("artists/" + encode(id));
        if (json == null || json.get("id").isNull()) {
            return AudioReference.NO_TRACK;
        }

        var topTracks = this.apiGet("artists/" + encode(id) + "/top-tracks?market=" + encode(this.countryCode));
        var tracks = topTracks == null ? List.<AudioTrack>of() : this.parseTracks(topTracks.get("tracks").values(), preview);
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist(json.get("name").safeText() + " - Top Tracks", tracks, ExtendedAudioPlaylist.Type.ARTIST,
                json.get("external_urls").get("spotify").text(), firstImage(json), json.get("name").text(), tracks.size());
    }

    public AudioItem getRecommendations(String query, boolean preview) throws IOException {
        Matcher matcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
        if (matcher.find()) {
            var seedType = matcher.group("seedType");
            var seed = matcher.group("seed");

            // The resolver recommends from tracks only. Album and artist mixes, and track mixes
            // the resolver has no result for, use Spotify's inspired-by mix.
            if ("track".equals(seedType) && this.resolvePlaylistUrl != null) {
                var recommended = this.getResolverRecommendations("spotify:track:" + seed, 1, preview);
                if (recommended != null) {
                    return recommended;
                }
            }
            return this.getInspiredByMix(seedType, seed, preview);
        }

        if (this.resolvePlaylistUrl == null) {
            log.warn("Spotify recommendations need resolvePlaylistUrl (a Sonara-API resolver).");
            return AudioReference.NO_TRACK;
        }

        // Other sprec: queries are a Spotify track url, uri or id
        var recommended = this.getResolverRecommendations(query, 0, preview);
        return recommended == null ? AudioReference.NO_TRACK : recommended;
    }

    // ---- Sonara-API resolver ----

    // Loads any playlist from the Sonara-API resolver. Returns null when it fails, so the Web API is used instead.
    @Nullable
    private AudioItem getPlaylistFromResolver(String id, boolean preview) {
        try {
            var json = this.resolverGet("/playlist", "/" + encode(id));
            if (json == null || json.isNull()) {
                return null;
            }

            var tracks = new ArrayList<AudioTrack>();
            for (var item : json.get("tracks").get("items").values()) {
                var track = this.parseTrack(item.get("track"), preview);
                if (track != null) {
                    tracks.add(track);
                }
            }
            if (tracks.isEmpty()) {
                return null;
            }

            return new SpotifyAudioPlaylist(json.get("name").textOrDefault("Spotify"), tracks, ExtendedAudioPlaylist.Type.PLAYLIST,
                    json.get("external_urls").get("spotify").text(), firstImage(json), json.get("owner").get("display_name").text(),
                    tracks.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load Spotify playlist {} from resolvePlaylistUrl, trying the Web API: {}", id, e.getMessage());
            return null;
        }
    }

    @Nullable
    private AudioItem getResolverRecommendations(String trackUrl, int limit, boolean preview) {
        try {
            var json = this.resolverGet("/recommendations", "?url=" + encode(trackUrl) + (limit > 0 ? "&limit=" + limit : ""));
            if (json == null) {
                return null;
            }

            var tracks = this.parseTracks(json.get("tracks").values(), preview);
            if (tracks.isEmpty()) {
                return null;
            }
            if (tracks.size() == 1) {
                return tracks.get(0);
            }
            return new SpotifyAudioPlaylist("Spotify Recommendations", tracks, ExtendedAudioPlaylist.Type.RECOMMENDATIONS,
                    null, null, null, tracks.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load Spotify recommendations from resolvePlaylistUrl: {}", e.getMessage());
            return null;
        }
    }

    // GET <resolvePlaylistUrl><route>[/full]<rest>. The /full routes add ISRCs (better mirroring)
    // and need the app credentials, which are sent as headers so they never end up in logged URLs.
    @Nullable
    private JsonBrowser resolverGet(String route, String rest) throws IOException {
        var full = this.hasCredentials();
        var request = authorizedGet(this.resolvePlaylistUrl + route + (full ? "/full" : "") + rest);
        if (full) {
            request.setHeader("X-Client-Id", this.clientId);
            request.setHeader("X-Client-Secret", this.clientSecret);
        }
        return this.fetchJson(request);
    }

    // ---- Spotify Web API ----

    private AudioItem getPlaylistFromWebApi(String id, boolean preview) throws IOException {
        var json = this.apiGet("playlists/" + encode(id) + "?market=" + encode(this.countryCode));
        if (json == null || json.get("id").isNull()) {
            if (id.startsWith(SPOTIFY_MADE_PLAYLIST_PREFIX)) {
                log.warn("Spotify-made playlist {} is not available on the Web API. Set resolvePlaylistUrl to a Sonara-API resolver to load it.", id);
            }
            return AudioReference.NO_TRACK;
        }

        var tracks = new ArrayList<AudioTrack>();
        var offset = 0;
        var pages = 0;
        while (true) {
            var page = this.apiGet("playlists/" + encode(id) + "/items?limit=" + PLAYLIST_MAX_PAGE_ITEMS + "&offset=" + offset
                    + "&market=" + encode(this.countryCode));
            if (page == null) {
                break;
            }
            for (var item : page.get("items").values()) {
                var track = this.parseTrack(item.get("track"), preview);
                if (track != null) {
                    tracks.add(track);
                }
            }
            offset += PLAYLIST_MAX_PAGE_ITEMS;
            if (page.get("next").text() == null || ++pages >= Math.max(this.playlistPageLimit, 1)) {
                break;
            }
        }

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist(json.get("name").safeText(), tracks, ExtendedAudioPlaylist.Type.PLAYLIST,
                json.get("external_urls").get("spotify").text(), firstImage(json), json.get("owner").get("display_name").text(),
                (int) json.get("tracks").get("total").asLong(tracks.size()));
    }

    private List<AudioTrack> getTracksById(List<String> ids, boolean preview) throws IOException {
        var tracks = new ArrayList<AudioTrack>();
        for (var i = 0; i < ids.size(); i += TRACKS_BATCH_SIZE) {
            var batch = String.join(",", ids.subList(i, Math.min(i + TRACKS_BATCH_SIZE, ids.size())));
            var json = this.apiGet("tracks?ids=" + encode(batch) + "&market=" + encode(this.countryCode));
            if (json != null) {
                tracks.addAll(this.parseTracks(json.get("tracks").values(), preview));
            }
        }
        return tracks;
    }

    @Nullable
    private JsonBrowser apiGet(String path) throws IOException {
        var request = new HttpGet(API_BASE + path);
        request.setHeader("Authorization", "Bearer " + this.getApiAccessToken());
        return this.fetchJson(request);
    }

    private String getApiAccessToken() throws IOException {
        if (this.hasCredentials()) {
            return this.getClientAccessToken();
        }
        if (this.anonymousTokenUrl != null) {
            return this.getAnonymousAccessToken();
        }
        throw new IllegalStateException("Spotify needs clientId and clientSecret (or anonymousTokenUrl) to load tracks.");
    }

    private String getClientAccessToken() throws IOException {
        var now = System.currentTimeMillis();
        if (this.clientAccessToken != null && now < this.clientAccessTokenExpirationMs - 5000) {
            return this.clientAccessToken;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            if (this.clientAccessToken != null && now < this.clientAccessTokenExpirationMs - 5000) {
                return this.clientAccessToken;
            }

            var request = new HttpPost(ACCOUNTS_TOKEN_URL);
            request.setHeader("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((this.clientId + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)));
            request.setEntity(new StringEntity("grant_type=client_credentials", ContentType.APPLICATION_FORM_URLENCODED));

            var json = this.fetchJson(request);
            var accessToken = json == null ? null : json.get("access_token").text();
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Spotify did not return an access token. Check clientId and clientSecret.");
            }

            this.clientAccessToken = accessToken;
            this.clientAccessTokenExpirationMs = now + json.get("expires_in").asLong(3600) * 1000;
            return this.clientAccessToken;
        }
    }

    // ---- Inspired-by mixes (need the anonymous web player token) ----

    private AudioItem getInspiredByMix(String seedType, String seed, boolean preview) throws IOException {
        if (this.anonymousTokenUrl == null) {
            log.warn("Spotify {} mixes need anonymousTokenUrl.", seedType);
            return AudioReference.NO_TRACK;
        }

        var request = new HttpGet(CLIENT_API_BASE + "inspiredby-mix/v2/seed_to_playlist/spotify:"
                + seedType + ":" + seed + "?response-format=json");
        request.setHeader("Authorization", "Bearer " + this.getAnonymousAccessToken());

        var json = this.fetchJson(request);
        var mediaItems = json == null ? null : json.get("mediaItems");
        if (mediaItems != null && mediaItems.isList() && !mediaItems.values().isEmpty()) {
            var uri = mediaItems.index(0).get("uri").safeText();
            var parts = uri == null ? new String[0] : uri.split(":");
            if (parts.length >= 3) {
                var item = this.getPlaylist(parts[2], preview);
                if (!"track".equals(seedType)) {
                    return item;
                }
                var seedTrack = this.getTrack(seed, false);
                return this.filterMixPlaylist(item, seedTrack instanceof AudioTrack track ? track : null);
            }
        }
        return AudioReference.NO_TRACK;
    }

    private AudioItem filterMixPlaylist(AudioItem item, AudioTrack seedTrack) {
        if (item instanceof AudioTrack track) {
            return this.shouldSkipMixTrack(track, seedTrack, new LinkedHashSet<>()) ? AudioReference.NO_TRACK : track;
        }

        if (!(item instanceof AudioPlaylist playlist)) {
            return item;
        }

        var seenTitles = new LinkedHashSet<String>();
        var filteredTracks = playlist.getTracks().stream()
                .filter(track -> !this.shouldSkipMixTrack(track, seedTrack, seenTitles))
                .collect(Collectors.toCollection(ArrayList::new));

        if (filteredTracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        if (filteredTracks.size() == 1) {
            return filteredTracks.get(0);
        }
        return new BasicAudioPlaylist(playlist.getName(), filteredTracks, null, playlist.isSearchResult());
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
        return !normalizedTitle.isBlank() && !seenTitles.add(normalizedTitle);
    }

    private static String normalizeMixTitle(String title) {
        if (title == null) {
            return "";
        }

        return title.toLowerCase()
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("\\[.*?\\]", " ")
                .replaceAll("\\s+-\\s+.*$", " ")
                .replaceAll("\\s+", " ")
                .trim();
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

            var tokenJson = this.fetchJson(authorizedGet(this.anonymousTokenUrl));
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

    // ---- Parsing ----

    // Reads a track in the Spotify Web API format (also returned by Sonara-API). Returns null for local files and episodes.
    @Nullable
    private AudioTrack parseTrack(@Nullable JsonBrowser track, boolean preview) {
        if (track == null || track.isNull() || track.get("is_local").asBoolean(false) || "episode".equals(track.get("type").text())) {
            return null;
        }

        var id = track.get("id").text();
        if (id == null || id.isBlank()) {
            return null;
        }

        var artists = track.get("artists").values();
        var album = track.get("album");
        var artwork = firstImage(album);
        var albumId = album.get("id").text();
        var artistId = artists.isEmpty() ? null : artists.get(0).get("id").text();
        var previewUrl = track.get("preview_url").text();
        var isPreview = preview && previewUrl != null;

        var info = new AudioTrackInfo(
                track.get("name").text(),
                joinArtists(track.get("artists")),
                isPreview ? PREVIEW_LENGTH : track.get("duration_ms").asLong(0),
                id,
                false,
                "https://open.spotify.com/track/" + id,
                artwork,
                track.get("external_ids").get("isrc").text());

        return new SpotifyAudioTrack(
                info,
                album.get("name").text(),
                albumId == null || albumId.isBlank() ? null : "https://open.spotify.com/album/" + albumId,
                artistId == null || artistId.isBlank() ? null : "https://open.spotify.com/artist/" + artistId,
                artwork,
                previewUrl,
                isPreview,
                this);
    }

    private List<AudioTrack> parseTracks(List<JsonBrowser> values, boolean preview) {
        var tracks = new ArrayList<AudioTrack>();
        for (var value : values) {
            var track = this.parseTrack(value, preview);
            if (track != null) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    @Nullable
    private static String firstImage(@Nullable JsonBrowser json) {
        if (json == null || json.isNull()) {
            return null;
        }
        var images = json.get("images").values();
        return images.isEmpty() ? null : images.get(0).get("url").text();
    }

    private static String joinArtists(JsonBrowser artists) {
        return artists.values().stream()
                .map(artist -> artist.get("name").text())
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(", "));
    }

    private static String findTextValue(JsonBrowser json, String key) {
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

        for (var value : json.values()) {
            var text = findTextValue(value, key);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static long findLongValue(JsonBrowser json, String key) {
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

        for (var value : json.values()) {
            var nestedValue = findLongValue(value, key);
            if (nestedValue > 0) {
                return nestedValue;
            }
        }
        return 0;
    }

    // ---- HTTP ----

    @Nullable
    private JsonBrowser fetchJson(HttpUriRequest request) throws IOException {
        try (var httpInterface = this.httpInterfaceManager.getInterface()) {
            return SonaraTools.fetchResponseAsJson(httpInterface, request);
        }
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

    private boolean hasCredentials() {
        return this.clientId != null && this.clientSecret != null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
