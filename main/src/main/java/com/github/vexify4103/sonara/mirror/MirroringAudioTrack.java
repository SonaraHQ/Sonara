package com.github.vexify4103.sonara.mirror;

import com.github.vexify4103.sonara.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class MirroringAudioTrack extends ExtendedAudioTrack {

	private static final Logger log = LoggerFactory.getLogger(MirroringAudioTrack.class);

	public static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(15);

	protected final MirroringAudioSourceManager sourceManager;
	protected MirrorResolutionContext resolutionContext;

	public MirroringAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl,
	                           String artistUrl, String artistArtworkUrl, String previewUrl,
	                           boolean isPreview, MirroringAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview);
		this.sourceManager = sourceManager;
	}

	protected abstract InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream);

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		if (this.resolutionContext == null) {
			this.resolutionContext = new MirrorResolutionContext(
				this.sourceManager.getSourceName(),
				this.getIdentifier()
			);
		}
		processWithContext(executor, this.resolutionContext);
	}

	protected void processWithContext(LocalAudioTrackExecutor executor,
	                                   MirrorResolutionContext ctx) throws Exception {
		if (ctx.isDeadlineExceeded()) {
			throw new FriendlyException("Mirror resolution deadline exceeded.",
				FriendlyException.Severity.COMMON, null);
		}
		if (this.isPreview) {
			if (this.previewUrl == null) {
				throw new FriendlyException("No preview url found", FriendlyException.Severity.COMMON,
					new IllegalArgumentException());
			}
			try (var httpInterface = this.sourceManager.getHttpInterface()) {
				try (var stream = new PersistentHttpStream(httpInterface, new URI(this.previewUrl),
					this.trackInfo.length)) {
					processDelegate(createAudioTrack(this.trackInfo, stream), executor);
				}
			}
			return;
		}
		// When a mirror fails before any audio was played (for example Deezer refusing to stream it),
		// the next provider is tried. Providers that were already tried are skipped by the resolver.
		Exception playbackFailure = null;
		var failedMirrors = new HashSet<String>();
		while (true) {
			InternalAudioTrack mirror;
			try {
				mirror = this.resolveMirror(ctx);
			} catch (TrackNotFoundException e) {
				if (playbackFailure != null) {
					throw playbackFailure;
				}
				throw e;
			}

			var mirrorSource = mirror.getSourceManager() != null ? mirror.getSourceManager().getSourceName() : "unknown";
			var mirrorKey = mirrorSource + ":" + mirror.getIdentifier();
			if (failedMirrors.contains(mirrorKey)) {
				throw playbackFailure;
			}

			mirror.setUserData(this.getUserData());
			log.debug("Loaded track mirror from {} {}({}) ", mirrorSource, mirror.getInfo().title, mirror.getInfo().uri);

			long startPosition = executor.getPosition();
			try {
				processDelegate(mirror, executor);
				return;
			} catch (Exception e) {
				if (executor.getPosition() != startPosition || isInterruption(e) || ctx.isDeadlineExceeded()) {
					throw e;
				}
				log.warn("[Mirror] Playback from {} failed for '{}' before any audio was played, trying the next provider: {}",
					mirrorSource, this.trackInfo.title, e.getMessage());
				executor.getAudioBuffer().clear();
				failedMirrors.add(mirrorKey);
				playbackFailure = e;
			}
		}
	}

	private InternalAudioTrack resolveMirror(MirrorResolutionContext ctx) {
		var item = this.sourceManager.getResolver().apply(this, ctx);

		if (item instanceof AudioPlaylist) {
			var tracks = ((AudioPlaylist) item).getTracks();
			if (tracks.isEmpty()) {
				throw new TrackNotFoundException("No tracks found in playlist or search result for track");
			}
			item = tracks.get(0);
		}
		if (item instanceof InternalAudioTrack) {
			return (InternalAudioTrack) item;
		}
		throw new TrackNotFoundException("No mirror found for track");
	}

	// stopping a track interrupts the playing thread, that must not be retried with another provider
	private static boolean isInterruption(Throwable error) {
		if (Thread.currentThread().isInterrupted()) {
			return true;
		}
		for (var cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedException || cause instanceof ClosedByInterruptException) {
				return true;
			}
			if (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException)
				&& (cause.getMessage() == null || !cause.getMessage().contains("timed out"))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}

	public AudioItem loadItem(String query) {
		var cf = new CompletableFuture<AudioItem>();
		this.sourceManager.getAudioPlayerManager().loadItem(query, new AudioLoadResultHandler() {

			@Override
			public void trackLoaded(AudioTrack track) {
				log.debug("Track loaded: {}", track.getIdentifier());
				cf.complete(track);
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				log.debug("Playlist loaded: {}", playlist.getName());
				cf.complete(playlist);
			}

			@Override
			public void noMatches() {
				log.debug("No matches found for: {}", query);
				cf.complete(AudioReference.NO_TRACK);
			}

			@Override
			public void loadFailed(FriendlyException exception) {
				log.debug("Failed to load: {}", query);
				cf.completeExceptionally(exception);
			}
		});
		try {
			return cf.get(DEFAULT_PROVIDER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			cf.cancel(true);
			log.warn("Provider timed out after {}ms for '{}'", DEFAULT_PROVIDER_TIMEOUT.toMillis(), query);
			return AudioReference.NO_TRACK;
		} catch (CancellationException | InterruptedException e) {
			Thread.currentThread().interrupt();
			cf.cancel(true);
			return AudioReference.NO_TRACK;
		} catch (Exception e) {
			cf.cancel(true);
			log.error("Provider load failed for '{}': {}", query, e.getMessage());
			return AudioReference.NO_TRACK;
		}
	}
}