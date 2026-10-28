package com.github.vexify4103.sonara.genius;

import com.github.topi314.lavalyrics.AudioLyricsManager;
import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.github.vexify4103.sonara.SonaraTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Plain lyrics from Genius. Searches Genius for the track, then reads the lyrics from the song page.
 * Genius has no timestamps, so the lyrics have text but no lines.
 */
public class GeniusLyricsManager implements AudioLyricsManager {

	private static final Logger log = LoggerFactory.getLogger(GeniusLyricsManager.class);
	private static final String SEARCH_URL = "https://genius.com/api/search/song?per_page=5&q=";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
	// Video and release noise in titles, for example "(Official Video)" or "[Remastered]"
	private static final Pattern TITLE_NOISE = Pattern.compile(
		"[(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|mv|explicit|clean)\\b[^)\\]]*[)\\]]",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern FEATURING = Pattern.compile("\\s+[(\\[]?(feat\\.?|ft\\.?|featuring)\\s.*$", Pattern.CASE_INSENSITIVE);

	private final HttpInterfaceManager httpInterfaceManager;

	public GeniusLyricsManager() {
		this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
		this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
			.setConnectTimeout(5000)
			.setConnectionRequestTimeout(5000)
			.setSocketTimeout(10000)
			.build());
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "genius";
	}

	@Override
	public @Nullable AudioLyrics loadLyrics(@NotNull AudioTrack audioTrack) {
		var info = audioTrack.getInfo();
		var artist = cleanArtist(info.author);
		var title = cleanTitle(info.title, artist);
		if (title.isBlank()) {
			return null;
		}

		try {
			var songUrl = this.findSongUrl(title, artist);
			if (songUrl == null) {
				return null;
			}
			var text = this.fetchLyricsText(songUrl);
			if (text == null || text.isBlank()) {
				return null;
			}
			return new BasicAudioLyrics("genius", "Genius", text, List.of());
		} catch (IOException e) {
			// Lyrics are optional; a Genius timeout must not fail the lyrics request
			log.debug("Genius lyrics lookup failed for {} - {}: {}", artist, title, e.getMessage());
			return null;
		}
	}

	// Picks the first search hit that is not a Genius translation page, preferring hits by the track's artist
	@Nullable
	private String findSongUrl(String title, String artist) throws IOException {
		var request = new HttpGet(SEARCH_URL + URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8));
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("Accept", "application/json");

		JsonBrowser json;
		try (var httpInterface = this.httpInterfaceManager.getInterface()) {
			json = SonaraTools.fetchResponseAsJson(httpInterface, request);
		}
		if (json == null) {
			return null;
		}

		String fallback = null;
		for (var section : json.get("response").get("sections").values()) {
			for (var hit : section.get("hits").values()) {
				var result = hit.get("result");
				var primaryArtist = result.get("primary_artist").get("name").safeText();
				if (result.get("instrumental").asBoolean(false) || primaryArtist.startsWith("Genius ")) {
					continue;
				}
				var url = result.get("url").text();
				if (url == null) {
					continue;
				}
				if (!artist.isBlank() && (looseIncludes(primaryArtist, artist) || looseIncludes(artist, primaryArtist))) {
					return url;
				}
				if (fallback == null && looseIncludes(result.get("title").safeText(), title)) {
					fallback = url;
				}
			}
		}
		return fallback;
	}

	@Nullable
	private String fetchLyricsText(String songUrl) throws IOException {
		var request = new HttpGet(songUrl);
		request.setHeader("User-Agent", USER_AGENT);

		String html;
		try (var httpInterface = this.httpInterfaceManager.getInterface();
			 var response = httpInterface.execute(request)) {
			if (response.getStatusLine().getStatusCode() != 200) {
				return null;
			}
			html = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}

		var document = Jsoup.parse(html);
		var builder = new StringBuilder();
		for (Element container : document.select("div[data-lyrics-container=true]")) {
			// Song headers and other page parts that Genius marks as not part of the lyrics
			container.select("[data-exclude-from-selection=true]").remove();
			container.select("br").after("\n");
			var text = container.wholeText().strip();
			if (!text.isEmpty()) {
				if (builder.length() > 0) {
					builder.append("\n\n");
				}
				builder.append(text);
			}
		}
		return builder.toString().replaceAll("\n{3,}", "\n\n").strip();
	}

	private static String cleanTitle(String title, String artist) {
		if (title == null) {
			return "";
		}
		var cleaned = TITLE_NOISE.matcher(title).replaceAll("");
		cleaned = FEATURING.matcher(cleaned).replaceAll("");
		// "Artist - Title" video titles, only when the part before the dash is the artist
		var dash = cleaned.indexOf(" - ");
		if (dash > 0) {
			var prefix = cleaned.substring(0, dash);
			if (artist.isBlank() || looseIncludes(prefix, artist) || looseIncludes(artist, prefix)) {
				cleaned = cleaned.substring(dash + 3);
			}
		}
		return cleaned.replaceAll("\\s{2,}", " ").strip();
	}

	private static String cleanArtist(String artist) {
		if (artist == null) {
			return "";
		}
		return artist.replaceAll("(?i)\\s*-\\s*topic$", "")
			.replaceAll("(?i)vevo$", "")
			.split("\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?)\\s*")[0]
			.strip();
	}

	private static boolean looseIncludes(String haystack, String needle) {
		var left = fold(haystack);
		var right = fold(needle);
		return !left.isEmpty() && !right.isEmpty() && left.contains(right);
	}

	private static String fold(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}
}
