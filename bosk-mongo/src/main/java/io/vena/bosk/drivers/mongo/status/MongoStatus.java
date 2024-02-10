package io.vena.bosk.drivers.mongo.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vena.bosk.drivers.mongo.Manifest;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Info about the state of the database, highlighting how it differs from the desired state.
 */
public record MongoStatus(
	@JsonInclude(NON_NULL) String error,
	ManifestStatus manifest,
	StateStatus state
) {
	public MongoStatus with(DatabaseFormat preferredFormat, Manifest actualManifest) {
		return new MongoStatus(
			this.error,
			new ManifestStatus(
				Manifest.forFormat(preferredFormat),
				actualManifest
			),
			this.state
		);
	}

	public boolean isAllClear() {
		return manifest.isIdentical()
			&& state.difference() instanceof NoDifference;
	}
}
