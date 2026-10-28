package com.github.vexify4103.sonara.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import static com.github.vexify4103.sonara.mirror.MirroringAudioSourceManager.ISRC_PATTERN;
import static com.github.vexify4103.sonara.mirror.MirroringAudioSourceManager.QUERY_PATTERN;

@ConfigurationProperties(prefix = "plugins.sonara")
@Component
public class SonaraConfig {

	private String[] providers = {
		"ytsearch:\"" + ISRC_PATTERN + "\"",
		"ytsearch:" + QUERY_PATTERN
	};

	public String[] getProviders() {
		return this.providers;
	}

	public void setProviders(String[] providers) {
		this.providers = providers;
	}

}
