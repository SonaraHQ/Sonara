package com.github.vexify4103.sonara.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.sonara.amazonmusic")
@Component
public class AmazonMusicConfig {

	private int searchLimit = 10;

	public int getSearchLimit() {
		return this.searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}
}
