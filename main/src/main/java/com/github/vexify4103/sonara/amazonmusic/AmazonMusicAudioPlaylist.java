package com.github.vexify4103.sonara.amazonmusic;

import com.github.vexify4103.sonara.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.List;

public class AmazonMusicAudioPlaylist extends ExtendedAudioPlaylist {

	public AmazonMusicAudioPlaylist(String name, List<AudioTrack> tracks, ExtendedAudioPlaylist.Type type, String url, String artworkURL, String author, Integer totalTracks) {
		super(name, tracks, type, url, artworkURL, author, totalTracks);
	}
}
