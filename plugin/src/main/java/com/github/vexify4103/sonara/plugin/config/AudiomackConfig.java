package com.github.vexify4103.sonara.plugin.config;

import com.github.vexify4103.sonara.audiomack.AudiomackAudioSourceManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.sonara.audiomack")
@Component
public class AudiomackConfig {
	private int searchLimit = 10;
	private int artistTrackLimit = 25;
	private String consumerKey;
	private String consumerSecret;

	public int getSearchLimit() {
		return searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public int getArtistTrackLimit() {
		return artistTrackLimit;
	}

	public void setArtistTrackLimit(int artistTrackLimit) {
		this.artistTrackLimit = artistTrackLimit;
	}

	public String getConsumerKey() {
		return consumerKey;
	}

	public void setConsumerKey(String consumerKey) {
		this.consumerKey = consumerKey;
	}

	public String getConsumerSecret() {
		return consumerSecret;
	}

	public void setConsumerSecret(String consumerSecret) {
		this.consumerSecret = consumerSecret;
	}

	public AudiomackAudioSourceManager.AudiomackConfig buildConfig() {
		var config = new AudiomackAudioSourceManager.AudiomackConfig();
		config.setSearchLimit(this.searchLimit);
		config.setArtistTrackLimit(this.artistTrackLimit);
		config.setConsumerKey(this.consumerKey);
		config.setConsumerSecret(this.consumerSecret);
		return config;
	}
}
