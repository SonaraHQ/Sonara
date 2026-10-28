package com.github.vexify4103.sonara.pandora;

import com.github.vexify4103.sonara.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.List;

public class PandoraAudioPlaylist extends ExtendedAudioPlaylist {

	public PandoraAudioPlaylist(String name, List<AudioTrack> tracks, Type type, String url, String artworkURL, String author, Integer totalTracks) {
		super(name, tracks, type, url, artworkURL, author, totalTracks);
	}
}
