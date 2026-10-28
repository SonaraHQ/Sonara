package com.github.vexify4103.sonara.mirror;

import com.sedmelluq.discord.lavaplayer.track.AudioItem;

import java.util.function.BiFunction;

@FunctionalInterface
public interface MirroringAudioTrackResolver
		extends BiFunction<MirroringAudioTrack, MirrorResolutionContext, AudioItem> {
}
