package io.vena.bosk.drivers.mongo.status;

import io.vena.bosk.drivers.mongo.Manifest;

public record ManifestStatus(
	Manifest expected,
	Manifest actual
) {
	public boolean isIdentical() {
		return expected.equals(actual);
	}
}
