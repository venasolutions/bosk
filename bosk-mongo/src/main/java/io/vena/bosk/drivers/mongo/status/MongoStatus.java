package io.vena.bosk.drivers.mongo.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vena.bosk.drivers.mongo.Manifest;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Info about the state of the database, highlighting how it differs from the desired state.
 */
public record MongoStatus(
	@JsonInclude(NON_NULL) String error,
	Manifest manifest,
	Long stateBytes
) { }
