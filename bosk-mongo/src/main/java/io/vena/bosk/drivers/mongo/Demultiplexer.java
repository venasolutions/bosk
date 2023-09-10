package io.vena.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Value;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.Document;

import static java.util.Objects.requireNonNull;

class Demultiplexer {
	private final Map<TransactionID, List<ChangeStreamDocument<Document>>> transactionsInProgress = new ConcurrentHashMap<>();

	public void add(ChangeStreamDocument<Document> event) {
		TransactionID key = TransactionID.from(event);
		transactionsInProgress
			.computeIfAbsent(key, __ -> new ArrayList<>())
			.add(event);
	}

	public List<ChangeStreamDocument<Document>> pop(ChangeStreamDocument<Document> finalEvent) {
		return transactionsInProgress.remove(TransactionID.from(finalEvent));
	}

	@Value
	private static class TransactionID {
		BsonDocument lsid;
		BsonInt64 txnNumber;

		public static TransactionID from(ChangeStreamDocument<?> event) {
			return new TransactionID(requireNonNull(event.getLsid()), requireNonNull(event.getTxnNumber()));
		}
	}
}
