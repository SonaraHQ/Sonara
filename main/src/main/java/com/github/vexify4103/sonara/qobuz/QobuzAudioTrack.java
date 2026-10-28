package com.github.vexify4103.sonara.qobuz;

import com.github.vexify4103.sonara.mirror.MirroringAudioSourceManager;
import com.github.vexify4103.sonara.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public class QobuzAudioTrack extends MirroringAudioTrack {

	public QobuzAudioTrack(AudioTrackInfo trackInfo, QobuzAudioSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, sourceManager);
	}

	public QobuzAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, QobuzAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false, sourceManager);
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
		return new Mp3AudioTrack(trackInfo, stream);
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new QobuzAudioTrack(this.trackInfo, this.albumName, this.albumUrl, this.artistUrl, this.artistArtworkUrl, (QobuzAudioSourceManager) this.sourceManager);
	}

}
