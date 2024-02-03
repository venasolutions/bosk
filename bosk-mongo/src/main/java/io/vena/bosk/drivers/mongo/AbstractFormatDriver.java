package io.vena.bosk.drivers.mongo;

import io.vena.bosk.MapValue;
import io.vena.bosk.RootReference;
import io.vena.bosk.StateTreeNode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonInt64;

import static io.vena.bosk.drivers.mongo.Formatter.REVISION_ZERO;

@RequiredArgsConstructor
abstract class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final Formatter formatter;

	@Override
	public StateAndMetadata<R> loadAllState() throws IOException, UninitializedCollectionException {
		BsonState bsonState = loadBsonState();
		if (bsonState.state() == null) {
			throw new IOException("No existing state in document");
		}

		R root = formatter.document2object(bsonState.state(), rootRef);
		BsonInt64 revision = bsonState.revision() == null ? REVISION_ZERO : bsonState.revision();
		MapValue<String> diagnosticAttributes = bsonState.diagnosticAttributes() == null
			? MapValue.empty()
			: formatter.decodeDiagnosticAttributes(bsonState.diagnosticAttributes());

		return new StateAndMetadata<>(root, revision, diagnosticAttributes);
	}

	/**
	 * Low-level read of the database contents, with only the minimum interpretation
	 * necessary to determine what the various parts correspond to.
	 *
	 * @return the contents of the database; fields of the returned
	 * record can be null if they don't exist in the database.
	 */
	abstract BsonState loadBsonState() throws UninitializedCollectionException;

	record BsonState(
		BsonDocument state,
		BsonInt64 revision,
		BsonDocument diagnosticAttributes
	){}
}
