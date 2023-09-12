package io.vena.bosk.drivers.mongo;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.ClientSession;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MapReduceIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.CreateIndexOptions;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.DropIndexOptions;
import com.mongodb.client.model.EstimatedDocumentCountOptions;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.InsertOneOptions;
import com.mongodb.client.model.RenameCollectionOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@SuppressWarnings("NullableProblems")
@RequiredArgsConstructor(staticName = "of")
class TransactionalCollection<TDocument> implements MongoCollection<TDocument> {
	private final MongoCollection<TDocument> downstream;
	private final MongoClient mongoClient;
	private final ThreadLocal<ClientSession> currentSession = new ThreadLocal<>();
	private static final AtomicLong identityCounter = new AtomicLong(1);

	public Transaction newTransaction() {
		return new Transaction();
	}

	public class Transaction implements AutoCloseable {
		final String name;
		final String oldMDC;
		final boolean isNested;

		public Transaction() {
			name = "t" + identityCounter.getAndIncrement();
			oldMDC = MDC.get(MDC_KEY);
			isNested = (currentSession.get() != null);
			if (isNested) {
				LOGGER.debug("Enter nested transaction {}", name);
			} else {
				ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
					.causallyConsistent(true)
					.defaultTransactionOptions(TransactionOptions.builder()
						.writeConcern(WriteConcern.MAJORITY)
						.readConcern(ReadConcern.MAJORITY)
						.readPreference(ReadPreference.primary())
						.build())
					.build();
				ClientSession session = mongoClient.startSession(sessionOptions);
				session.startTransaction();
				currentSession.set(session);
				MDC.put(MDC_KEY, name);
				LOGGER.debug("Begin transaction {}", name);
			}
		}

		public void commit() {
			currentSession.get().commitTransaction();
		}

		/**
		 * Not strictly necessary, because this is the default if the transaction
		 * is not committed; however, it makes the calling code more self-documenting.
		 */
		public void abort() {
			LOGGER.debug("Abort transaction {}", name);
			currentSession.get().abortTransaction();
		}

		@Override
		public void close() {
			if (isNested) {
				LOGGER.debug("Exiting nested transaction {}", name);
			} else {
				LOGGER.debug("Close transaction {}", name);
				currentSession.get().close();
				currentSession.remove();
			}
			MDC.put(MDC_KEY, oldMDC);
		}

		private static final String MDC_KEY = "MongoDriver.transaction";
	}

	private ClientSession currentSession() {
		ClientSession clientSession = currentSession.get();
		if (clientSession == null) {
			throw new IllegalStateException("No active session");
		}
		return clientSession;
	}

	public MongoNamespace getNamespace() {
		return this.downstream.getNamespace();
	}

	public Class<TDocument> getDocumentClass() {
		return this.downstream.getDocumentClass();
	}

	public CodecRegistry getCodecRegistry() {
		return this.downstream.getCodecRegistry();
	}

	public ReadPreference getReadPreference() {
		return this.downstream.getReadPreference();
	}

	public WriteConcern getWriteConcern() {
		return this.downstream.getWriteConcern();
	}

	public ReadConcern getReadConcern() {
		return this.downstream.getReadConcern();
	}

	public <NewTDocument> MongoCollection<NewTDocument> withDocumentClass(Class<NewTDocument> clazz) {
		return this.downstream.withDocumentClass(clazz);
	}

	public MongoCollection<TDocument> withCodecRegistry(CodecRegistry codecRegistry) {
		return this.downstream.withCodecRegistry(codecRegistry);
	}

	public MongoCollection<TDocument> withReadPreference(ReadPreference readPreference) {
		return this.downstream.withReadPreference(readPreference);
	}

	public MongoCollection<TDocument> withWriteConcern(WriteConcern writeConcern) {
		return this.downstream.withWriteConcern(writeConcern);
	}

	public MongoCollection<TDocument> withReadConcern(ReadConcern readConcern) {
		return this.downstream.withReadConcern(readConcern);
	}

	public long countDocuments() {
		return this.downstream.countDocuments(currentSession());
	}

	public long countDocuments(Bson filter) {
		return this.downstream.countDocuments(currentSession(), filter);
	}

	public long countDocuments(Bson filter, CountOptions options) {
		return this.downstream.countDocuments(currentSession(), filter, options);
	}

	public long countDocuments(ClientSession clientSession) {
		return this.downstream.countDocuments(clientSession);
	}

	public long countDocuments(ClientSession clientSession, Bson filter) {
		return this.downstream.countDocuments(clientSession, filter);
	}

	public long countDocuments(ClientSession clientSession, Bson filter, CountOptions options) {
		return this.downstream.countDocuments(clientSession, filter, options);
	}

	public long estimatedDocumentCount() {
		return this.downstream.estimatedDocumentCount();
	}

	public long estimatedDocumentCount(EstimatedDocumentCountOptions options) {
		return this.downstream.estimatedDocumentCount(options);
	}

	public <TResult> DistinctIterable<TResult> distinct(String fieldName, Class<TResult> resultClass) {
		return this.downstream.distinct(currentSession(), fieldName, resultClass);
	}

	public <TResult> DistinctIterable<TResult> distinct(String fieldName, Bson filter, Class<TResult> resultClass) {
		return this.downstream.distinct(currentSession(), fieldName, filter, resultClass);
	}

	public <TResult> DistinctIterable<TResult> distinct(ClientSession clientSession, String fieldName, Class<TResult> resultClass) {
		return this.downstream.distinct(clientSession, fieldName, resultClass);
	}

	public <TResult> DistinctIterable<TResult> distinct(ClientSession clientSession, String fieldName, Bson filter, Class<TResult> resultClass) {
		return this.downstream.distinct(clientSession, fieldName, filter, resultClass);
	}

	public FindIterable<TDocument> find() {
		return this.downstream.find(currentSession());
	}

	public <TResult> FindIterable<TResult> find(Class<TResult> resultClass) {
		return this.downstream.find(currentSession(), resultClass);
	}

	public FindIterable<TDocument> find(Bson filter) {
		return this.downstream.find(currentSession(), filter);
	}

	public <TResult> FindIterable<TResult> find(Bson filter, Class<TResult> resultClass) {
		return this.downstream.find(currentSession(), filter, resultClass);
	}

	public FindIterable<TDocument> find(ClientSession clientSession) {
		return this.downstream.find(clientSession);
	}

	public <TResult> FindIterable<TResult> find(ClientSession clientSession, Class<TResult> resultClass) {
		return this.downstream.find(clientSession, resultClass);
	}

	public FindIterable<TDocument> find(ClientSession clientSession, Bson filter) {
		return this.downstream.find(clientSession, filter);
	}

	public <TResult> FindIterable<TResult> find(ClientSession clientSession, Bson filter, Class<TResult> resultClass) {
		return this.downstream.find(clientSession, filter, resultClass);
	}

	public AggregateIterable<TDocument> aggregate(List<? extends Bson> pipeline) {
		return this.downstream.aggregate(currentSession(), pipeline);
	}

	public <TResult> AggregateIterable<TResult> aggregate(List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.aggregate(currentSession(), pipeline, resultClass);
	}

	public AggregateIterable<TDocument> aggregate(ClientSession clientSession, List<? extends Bson> pipeline) {
		return this.downstream.aggregate(clientSession, pipeline);
	}

	public <TResult> AggregateIterable<TResult> aggregate(ClientSession clientSession, List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.aggregate(clientSession, pipeline, resultClass);
	}

	public ChangeStreamIterable<TDocument> watch() {
		return this.downstream.watch(currentSession());
	}

	public <TResult> ChangeStreamIterable<TResult> watch(Class<TResult> resultClass) {
		return this.downstream.watch(currentSession(), resultClass);
	}

	public ChangeStreamIterable<TDocument> watch(List<? extends Bson> pipeline) {
		return this.downstream.watch(currentSession(), pipeline);
	}

	public <TResult> ChangeStreamIterable<TResult> watch(List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.watch(currentSession(), pipeline, resultClass);
	}

	public ChangeStreamIterable<TDocument> watch(ClientSession clientSession) {
		return this.downstream.watch(clientSession);
	}

	public <TResult> ChangeStreamIterable<TResult> watch(ClientSession clientSession, Class<TResult> resultClass) {
		return this.downstream.watch(clientSession, resultClass);
	}

	public ChangeStreamIterable<TDocument> watch(ClientSession clientSession, List<? extends Bson> pipeline) {
		return this.downstream.watch(clientSession, pipeline);
	}

	public <TResult> ChangeStreamIterable<TResult> watch(ClientSession clientSession, List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.watch(clientSession, pipeline, resultClass);
	}

	public MapReduceIterable<TDocument> mapReduce(String mapFunction, String reduceFunction) {
		return this.downstream.mapReduce(currentSession(), mapFunction, reduceFunction);
	}

	public <TResult> MapReduceIterable<TResult> mapReduce(String mapFunction, String reduceFunction, Class<TResult> resultClass) {
		return this.downstream.mapReduce(currentSession(), mapFunction, reduceFunction, resultClass);
	}

	public MapReduceIterable<TDocument> mapReduce(ClientSession clientSession, String mapFunction, String reduceFunction) {
		return this.downstream.mapReduce(clientSession, mapFunction, reduceFunction);
	}

	public <TResult> MapReduceIterable<TResult> mapReduce(ClientSession clientSession, String mapFunction, String reduceFunction, Class<TResult> resultClass) {
		return this.downstream.mapReduce(clientSession, mapFunction, reduceFunction, resultClass);
	}

	public BulkWriteResult bulkWrite(List<? extends WriteModel<? extends TDocument>> requests) {
		return this.downstream.bulkWrite(currentSession(), requests);
	}

	public BulkWriteResult bulkWrite(List<? extends WriteModel<? extends TDocument>> requests, BulkWriteOptions options) {
		return this.downstream.bulkWrite(currentSession(), requests, options);
	}

	public BulkWriteResult bulkWrite(ClientSession clientSession, List<? extends WriteModel<? extends TDocument>> requests) {
		return this.downstream.bulkWrite(clientSession, requests);
	}

	public BulkWriteResult bulkWrite(ClientSession clientSession, List<? extends WriteModel<? extends TDocument>> requests, BulkWriteOptions options) {
		return this.downstream.bulkWrite(clientSession, requests, options);
	}

	public InsertOneResult insertOne(TDocument document) {
		return this.downstream.insertOne(currentSession(), document);
	}

	public InsertOneResult insertOne(TDocument document, InsertOneOptions options) {
		return this.downstream.insertOne(currentSession(), document, options);
	}

	public InsertOneResult insertOne(ClientSession clientSession, TDocument document) {
		return this.downstream.insertOne(clientSession, document);
	}

	public InsertOneResult insertOne(ClientSession clientSession, TDocument document, InsertOneOptions options) {
		return this.downstream.insertOne(clientSession, document, options);
	}

	public InsertManyResult insertMany(List<? extends TDocument> documents) {
		return this.downstream.insertMany(currentSession(), documents);
	}

	public InsertManyResult insertMany(List<? extends TDocument> documents, InsertManyOptions options) {
		return this.downstream.insertMany(currentSession(), documents, options);
	}

	public InsertManyResult insertMany(ClientSession clientSession, List<? extends TDocument> documents) {
		return this.downstream.insertMany(clientSession, documents);
	}

	public InsertManyResult insertMany(ClientSession clientSession, List<? extends TDocument> documents, InsertManyOptions options) {
		return this.downstream.insertMany(clientSession, documents, options);
	}

	public DeleteResult deleteOne(Bson filter) {
		return this.downstream.deleteOne(currentSession(), filter);
	}

	public DeleteResult deleteOne(Bson filter, DeleteOptions options) {
		return this.downstream.deleteOne(currentSession(), filter, options);
	}

	public DeleteResult deleteOne(ClientSession clientSession, Bson filter) {
		return this.downstream.deleteOne(clientSession, filter);
	}

	public DeleteResult deleteOne(ClientSession clientSession, Bson filter, DeleteOptions options) {
		return this.downstream.deleteOne(clientSession, filter, options);
	}

	public DeleteResult deleteMany(Bson filter) {
		return this.downstream.deleteMany(currentSession(), filter);
	}

	public DeleteResult deleteMany(Bson filter, DeleteOptions options) {
		return this.downstream.deleteMany(currentSession(), filter, options);
	}

	public DeleteResult deleteMany(ClientSession clientSession, Bson filter) {
		return this.downstream.deleteMany(clientSession, filter);
	}

	public DeleteResult deleteMany(ClientSession clientSession, Bson filter, DeleteOptions options) {
		return this.downstream.deleteMany(clientSession, filter, options);
	}

	public UpdateResult replaceOne(Bson filter, TDocument replacement) {
		return this.downstream.replaceOne(currentSession(), filter, replacement);
	}

	public UpdateResult replaceOne(Bson filter, TDocument replacement, ReplaceOptions replaceOptions) {
		return this.downstream.replaceOne(currentSession(), filter, replacement, replaceOptions);
	}

	public UpdateResult replaceOne(ClientSession clientSession, Bson filter, TDocument replacement) {
		return this.downstream.replaceOne(clientSession, filter, replacement);
	}

	public UpdateResult replaceOne(ClientSession clientSession, Bson filter, TDocument replacement, ReplaceOptions replaceOptions) {
		return this.downstream.replaceOne(clientSession, filter, replacement, replaceOptions);
	}

	public UpdateResult updateOne(Bson filter, Bson update) {
		return this.downstream.updateOne(currentSession(), filter, update);
	}

	public UpdateResult updateOne(Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, Bson update) {
		return this.downstream.updateOne(clientSession, filter, update);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(clientSession, filter, update, updateOptions);
	}

	public UpdateResult updateOne(Bson filter, List<? extends Bson> update) {
		return this.downstream.updateOne(currentSession(), filter, update);
	}

	public UpdateResult updateOne(Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, List<? extends Bson> update) {
		return this.downstream.updateOne(clientSession, filter, update);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(clientSession, filter, update, updateOptions);
	}

	public UpdateResult updateMany(Bson filter, Bson update) {
		return this.downstream.updateMany(currentSession(), filter, update);
	}

	public UpdateResult updateMany(Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, Bson update) {
		return this.downstream.updateMany(clientSession, filter, update);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(clientSession, filter, update, updateOptions);
	}

	public UpdateResult updateMany(Bson filter, List<? extends Bson> update) {
		return this.downstream.updateMany(currentSession(), filter, update);
	}

	public UpdateResult updateMany(Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, List<? extends Bson> update) {
		return this.downstream.updateMany(clientSession, filter, update);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(clientSession, filter, update, updateOptions);
	}

	public TDocument findOneAndDelete(Bson filter) {
		return this.downstream.findOneAndDelete(currentSession(), filter);
	}

	public TDocument findOneAndDelete(Bson filter, FindOneAndDeleteOptions options) {
		return this.downstream.findOneAndDelete(currentSession(), filter, options);
	}

	public TDocument findOneAndDelete(ClientSession clientSession, Bson filter) {
		return this.downstream.findOneAndDelete(clientSession, filter);
	}

	public TDocument findOneAndDelete(ClientSession clientSession, Bson filter, FindOneAndDeleteOptions options) {
		return this.downstream.findOneAndDelete(clientSession, filter, options);
	}

	public TDocument findOneAndReplace(Bson filter, TDocument replacement) {
		return this.downstream.findOneAndReplace(currentSession(), filter, replacement);
	}

	public TDocument findOneAndReplace(Bson filter, TDocument replacement, FindOneAndReplaceOptions options) {
		return this.downstream.findOneAndReplace(currentSession(), filter, replacement, options);
	}

	public TDocument findOneAndReplace(ClientSession clientSession, Bson filter, TDocument replacement) {
		return this.downstream.findOneAndReplace(clientSession, filter, replacement);
	}

	public TDocument findOneAndReplace(ClientSession clientSession, Bson filter, TDocument replacement, FindOneAndReplaceOptions options) {
		return this.downstream.findOneAndReplace(clientSession, filter, replacement, options);
	}

	public TDocument findOneAndUpdate(Bson filter, Bson update) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update);
	}

	public TDocument findOneAndUpdate(Bson filter, Bson update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update, options);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, Bson update) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, Bson update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update, options);
	}

	public TDocument findOneAndUpdate(Bson filter, List<? extends Bson> update) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update);
	}

	public TDocument findOneAndUpdate(Bson filter, List<? extends Bson> update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update, options);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, List<? extends Bson> update) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, List<? extends Bson> update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update, options);
	}

	public void drop() {
		this.downstream.drop(currentSession());
	}

	public void drop(ClientSession clientSession) {
		this.downstream.drop(clientSession);
	}

	public String createIndex(Bson keys) {
		return this.downstream.createIndex(currentSession(), keys);
	}

	public String createIndex(Bson keys, IndexOptions indexOptions) {
		return this.downstream.createIndex(currentSession(), keys, indexOptions);
	}

	public String createIndex(ClientSession clientSession, Bson keys) {
		return this.downstream.createIndex(clientSession, keys);
	}

	public String createIndex(ClientSession clientSession, Bson keys, IndexOptions indexOptions) {
		return this.downstream.createIndex(clientSession, keys, indexOptions);
	}

	public List<String> createIndexes(List<IndexModel> indexes) {
		return this.downstream.createIndexes(currentSession(), indexes);
	}

	public List<String> createIndexes(List<IndexModel> indexes, CreateIndexOptions createIndexOptions) {
		return this.downstream.createIndexes(currentSession(), indexes, createIndexOptions);
	}

	public List<String> createIndexes(ClientSession clientSession, List<IndexModel> indexes) {
		return this.downstream.createIndexes(clientSession, indexes);
	}

	public List<String> createIndexes(ClientSession clientSession, List<IndexModel> indexes, CreateIndexOptions createIndexOptions) {
		return this.downstream.createIndexes(clientSession, indexes, createIndexOptions);
	}

	public ListIndexesIterable<Document> listIndexes() {
		return this.downstream.listIndexes(currentSession());
	}

	public <TResult> ListIndexesIterable<TResult> listIndexes(Class<TResult> resultClass) {
		return this.downstream.listIndexes(currentSession(), resultClass);
	}

	public ListIndexesIterable<Document> listIndexes(ClientSession clientSession) {
		return this.downstream.listIndexes(clientSession);
	}

	public <TResult> ListIndexesIterable<TResult> listIndexes(ClientSession clientSession, Class<TResult> resultClass) {
		return this.downstream.listIndexes(clientSession, resultClass);
	}

	public void dropIndex(String indexName) {
		this.downstream.dropIndex(currentSession(), indexName);
	}

	public void dropIndex(String indexName, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(currentSession(), indexName, dropIndexOptions);
	}

	public void dropIndex(Bson keys) {
		this.downstream.dropIndex(currentSession(), keys);
	}

	public void dropIndex(Bson keys, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(currentSession(), keys, dropIndexOptions);
	}

	public void dropIndex(ClientSession clientSession, String indexName) {
		this.downstream.dropIndex(clientSession, indexName);
	}

	public void dropIndex(ClientSession clientSession, Bson keys) {
		this.downstream.dropIndex(clientSession, keys);
	}

	public void dropIndex(ClientSession clientSession, String indexName, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(clientSession, indexName, dropIndexOptions);
	}

	public void dropIndex(ClientSession clientSession, Bson keys, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(clientSession, keys, dropIndexOptions);
	}

	public void dropIndexes() {
		this.downstream.dropIndexes(currentSession());
	}

	public void dropIndexes(ClientSession clientSession) {
		this.downstream.dropIndexes(clientSession);
	}

	public void dropIndexes(DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndexes(currentSession(), dropIndexOptions);
	}

	public void dropIndexes(ClientSession clientSession, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndexes(clientSession, dropIndexOptions);
	}

	public void renameCollection(MongoNamespace newCollectionNamespace) {
		this.downstream.renameCollection(currentSession(), newCollectionNamespace);
	}

	public void renameCollection(MongoNamespace newCollectionNamespace, RenameCollectionOptions renameCollectionOptions) {
		this.downstream.renameCollection(currentSession(), newCollectionNamespace, renameCollectionOptions);
	}

	public void renameCollection(ClientSession clientSession, MongoNamespace newCollectionNamespace) {
		this.downstream.renameCollection(clientSession, newCollectionNamespace);
	}

	public void renameCollection(ClientSession clientSession, MongoNamespace newCollectionNamespace, RenameCollectionOptions renameCollectionOptions) {
		this.downstream.renameCollection(clientSession, newCollectionNamespace, renameCollectionOptions);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalCollection.class);
}
