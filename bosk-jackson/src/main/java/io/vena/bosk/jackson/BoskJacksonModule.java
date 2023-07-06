package io.vena.bosk.jackson;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;

public abstract class BoskJacksonModule extends Module {

	@Override
	public String getModuleName() {
		return getClass().getSimpleName();
	}

	@Override
	public Version version() {
		return Version.unknownVersion();
	}

}
