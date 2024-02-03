package io.vena.bosk.drivers.mongo.status;

import io.vena.bosk.drivers.mongo.Manifest;

/**
 * Info about the state of the database, highlighting how it differs from the desired state.
 */
public record MongoStatus(
	String error,
	Manifest manifest,
	Long stateBytes
) { }
