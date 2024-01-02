package io.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import io.vena.bosk.drivers.mongo.MappedDiagnosticContext.MDCScope;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.var;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.drivers.mongo.Formatter.REVISION_ONE;
import static io.vena.bosk.drivers.mongo.Formatter.REVISION_ZERO;
import static io.vena.bosk.drivers.mongo.MappedDiagnosticContext.setupMDC;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * This is the driver returned to the user by {@link MongoDriver#factory}.
 * This class implements the fault tolerance framework in cooperation with {@link ChangeReceiver}.
 * It's mostly exception handling code and diagnostics.
 * The actual database interactions used to implement the {@link BoskDriver} methods,
 * as well as most interactions with the downstream driver,
 * are delegated to a {@link FormatDriver} object that can be swapped out dynamically
 * as the database evolves.
 */
public class MainDriver<R extends StateTreeNode> implements MongoDriver<R> {
	private final Bosk<R> bosk;
	private final ChangeReceiver receiver;
	private final MongoDriverSettings driverSettings;
	private final BsonPlugin bsonPlugin;
	private final BoskDriver<R> downstream;
	private final TransactionalCollection<BsonDocument> collection;
	private final Listener listener;
	final Formatter formatter;

	private final ReentrantLock formatDriverLock = new ReentrantLock();
	private final Condition formatDriverChanged = formatDriverLock.newCondition();

	private volatile FormatDriver<R> formatDriver = new DisconnectedDriver<>(new Exception("Driver not yet initialized"));
	private volatile boolean isClosed = false;

	public MainDriver(
		Bosk<R> bosk,
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin,
		BoskDriver<R> downstream
	) {
		try (MDCScope __ = setupMDC(bosk.name())) {
			this.bosk = bosk;
			this.driverSettings = driverSettings;
			this.bsonPlugin = bsonPlugin;
			this.downstream = downstream;

			MongoClient mongoClient = MongoClients.create(
				MongoClientSettings.builder(clientSettings)
					// By default, let's deal only with durable data that won't get rolled back
					.readConcern(ReadConcern.MAJORITY)
					.writeConcern(WriteConcern.MAJORITY)
					.build());
			MongoCollection<BsonDocument> rawCollection = mongoClient
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME, BsonDocument.class);
			this.collection = TransactionalCollection.of(rawCollection, mongoClient);
			LOGGER.debug("Using database \"{}\" collection \"{}\"", driverSettings.database(), COLLECTION_NAME);

			Type rootType = bosk.rootReference().targetType();
			this.listener = new Listener(new FutureTask<>(() -> doInitialRoot(rootType)));
			this.formatter = new Formatter(bosk, bsonPlugin);
			this.receiver = new ChangeReceiver(bosk.name(), listener, driverSettings, rawCollection);
		}
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, InterruptedException, IOException {
		try (MDCScope __ = beginDriverOperation("initialRoot({})", rootType)) {
			FutureTask<R> task = listener.taskRef.get();
			if (task == null) {
				throw new IllegalStateException("initialRoot has already run");
			}
			try {
				return task.get();
			} catch (ExecutionException e) {
				Throwable exception = e.getCause();
				if (exception instanceof DownstreamInitialRootException) {
					// Try to throw the downstream exception directly,
					// as though we had called it without using a FutureTask
					Throwable cause = exception.getCause();
					if (cause instanceof IOException) {
						throw (IOException) cause;
					} else if (cause instanceof InvalidTypeException) {
						throw (InvalidTypeException) cause;
					} else if (cause instanceof InterruptedException) {
						throw (InterruptedException) cause;
					} else if (cause instanceof RuntimeException) {
						throw (RuntimeException) cause;
					} else {
						throw new AssertionError("Unexpected exception during initialRoot: " + e.getClass().getSimpleName(), e);
					}
				} else if (exception instanceof InitialRootFailureException) {
					throw (InitialRootFailureException) exception;
				} else {
					throw new AssertionError("Exception from initialRoot was not wrapped in DownstreamInitialRootException: " + e.getClass().getSimpleName(), e);
				}
			} finally {
				// For better or worse, we're done initialRoot. Clear taskRef so that Listener
				// enters its normal steady-state mode where onConnectionSucceeded events cause the state
				// to be loaded from the database and submitted downstream.
				LOGGER.debug("Done initialRoot");
				listener.taskRef.set(null);
			}
		}
	}

	/**
	 * Executed on the thread that calls {@link #initialRoot}.
	 * <p>
	 * Should throw no exceptions except {@link DownstreamInitialRootException}.
	 *
	 * @throws DownstreamInitialRootException if we attempt to delegate {@link #initialRoot} to
	 * the {@link #downstream} driver and it throws an exception; this is a fatal initialization error.
	 * @throws InitialRootFailureException if unable to load the initial root from the database,
	 * and {@link InitialDatabaseUnavailableMode#FAIL} is active.
	 */
	private R doInitialRoot(Type rootType) {
		R root;
		quietlySetFormatDriver(new DisconnectedDriver<>(FAILURE_TO_COMPUTE_INITIAL_ROOT)); // Pessimistic fallback
		try (var __ = collection.newReadOnlySession()){
			FormatDriver<R> detectedDriver = detectFormat();
			StateAndMetadata<R> loadedState = detectedDriver.loadAllState();
			root = loadedState.state;
			detectedDriver.onRevisionToSkip(loadedState.revision);
			publishFormatDriver(detectedDriver);
		} catch (UninitializedCollectionException e) {
			LOGGER.debug("Database collection is uninitialized; will initialize using downstream.initialRoot");
			root = callDownstreamInitialRoot(rootType);
			try (var session = collection.newSession()) {
				FormatDriver<R> preferredDriver = newPreferredFormatDriver();
				preferredDriver.initializeCollection(new StateAndMetadata<>(root, REVISION_ZERO, bosk.diagnosticContext().getAttributes()));
				preferredDriver.onRevisionToSkip(REVISION_ONE); // initialRoot handles REVISION_ONE; downstream only needs to know about changes after that
				session.commitTransactionIfAny();
				// We can now publish the driver knowing that the transaction, if there is one, has committed
				publishFormatDriver(preferredDriver);
			} catch (RuntimeException | IOException e2) {
				LOGGER.warn("Failed to initialize database; disconnecting", e2);
				quietlySetFormatDriver(new DisconnectedDriver<>(e2));
			}
		} catch (RuntimeException | UnrecognizedFormatException | IOException e) {
			switch (driverSettings.initialDatabaseUnavailableMode()) {
				case FAIL:
					LOGGER.debug("Unable to load initial root from database; aborting initialization", e);
					throw new InitialRootFailureException("Unable to load initial state from MongoDB", e);
				case DISCONNECT:
					LOGGER.info("Unable to load initial root from database; will proceed with downstream.initialRoot", e);
					quietlySetFormatDriver(new DisconnectedDriver<>(e));
					root = callDownstreamInitialRoot(rootType);
					break;
				default:
					throw new AssertionError("Unknown " + InitialDatabaseUnavailableMode.class.getSimpleName() + ": " + driverSettings.initialDatabaseUnavailableMode());
			}
		}
		return root;
	}

	/**
	 * @throws DownstreamInitialRootException only
	 */
	private R callDownstreamInitialRoot(Type rootType) {
		R root;
		try {
			root = downstream.initialRoot(rootType);
		} catch (RuntimeException | Error | InvalidTypeException | IOException | InterruptedException e) {
			LOGGER.error("Downstream driver failed to compute initial root", e);
			throw new DownstreamInitialRootException("Fatal error: downstream driver failed to compute initial root", e);
		}
		return root;
	}

	/**
	 * Refurbish is the one operation that always <em>must</em> happen in a transaction,
	 * or else we could fail after deleting the existing contents but before rewriting them,
	 * which would be catastrophic.
	 */
	private void refurbishTransaction() throws IOException {
		collection.ensureTransactionStarted();
		try {
			// Design note: this operation shouldn't do any special coordination with
			// the receiver/listener system, because other replicas won't.
			// That system needs to cope with a refurbish operations without any help.
			StateAndMetadata<R> result = formatDriver.loadAllState();
			FormatDriver<R> newFormatDriver = newPreferredFormatDriver();
			collection.deleteMany(new BsonDocument());
			newFormatDriver.initializeCollection(result);
			collection.commitTransaction();
			publishFormatDriver(newFormatDriver);
		} catch (UninitializedCollectionException e) {
			throw new IOException("Unable to refurbish uninitialized database collection", e);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		doRetryableDriverOperation(()->{
			formatDriver.submitReplacement(target, newValue);
		}, "submitReplacement({})", target);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		doRetryableDriverOperation(()->{
			formatDriver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}, "submitConditionalReplacement({}, {}={})", target, precondition, requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		doRetryableDriverOperation(()->{
			formatDriver.submitInitialization(target, newValue);
		}, "submitInitialization({})", target);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		doRetryableDriverOperation(()->{
			formatDriver.submitDeletion(target);
		}, "submitDeletion({})", target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		doRetryableDriverOperation(() -> {
			formatDriver.submitConditionalDeletion(target, precondition, requiredValue);
		}, "submitConditionalDeletion({}, {}={})", target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try {
			RetryableOperation<IOException, InterruptedException> flushOperation = this::doFlush;
			try (MDCScope __ = beginDriverOperation("flush")) {
				try {
					flushOperation.run();
				} catch (DisconnectedException e) {
					LOGGER.debug("Driver is disconnected ({}); will wait and retry operation", e.getMessage());
					waitAndRetry(flushOperation, "flush");
				} catch (RevisionFieldDisruptedException e) {
					// TODO: Really, at the moment the damage is noticed, we should probably make the receiver reboot; but we currently have no way to do so!
					LOGGER.debug("Revision field has been disrupted; wait for receiver to notice something is wrong", e);
					waitAndRetry(flushOperation, "flush");
				} catch (FailedSessionException e) {
					LOGGER.debug("Cannot open MongoDB session; will wait and retry flush", e);
					waitAndRetry(flushOperation, "flush");
				}
			}
		} catch (DisconnectedException | FailedSessionException e) {
			throw new FlushFailureException(e);
		}
	}

	private void doFlush() throws IOException, InterruptedException {
		try (var ___ = collection.newReadOnlySession()) {
			formatDriver.flush();
		}
	}

	@Override
	public void refurbish() throws IOException {
		doRetryableDriverOperation(() -> {
			refurbishTransaction();
		}, "refurbish");
	}

	@Override
	public void close() {
		isClosed = true;
		receiver.close();
		formatDriver.close();
		// JFC, if mongoClient is already closed, this throws
		// mongoClient.close();
	}

	/**
	 * Contains logic that runs on the {@link ChangeReceiver}'s background thread
	 * to update {@link MainDriver}'s state in response to various occurrences.
	 */
	private class Listener implements ChangeListener {
		final AtomicReference<FutureTask<R>> taskRef;

		private Listener(FutureTask<R> initialRootAction) {
			this.taskRef = new AtomicReference<>(initialRootAction);
		}

		@Override
		public void onConnectionSucceeded() throws
			UnrecognizedFormatException,
			UninitializedCollectionException,
			InterruptedException,
			IOException,
			InitialRootActionException,
			TimeoutException
		{
			LOGGER.debug("onConnectionSucceeded");
			FutureTask<R> initialRootAction = this.taskRef.get();
			if (initialRootAction == null) {
				FormatDriver<R> newDriver;
				StateAndMetadata<R> loadedState;
				try (var __ = collection.newReadOnlySession()) {
					LOGGER.debug("Loading database state to submit to downstream driver");
					newDriver = detectFormat();
					loadedState = newDriver.loadAllState();
				}
				// Note: can't call downstream methods with a session open,
				// because that could run hooks, which could themselves submit
				// new updates, and those updates need their own session.

				// Update the FormatDriver before submitting the new state downstream in case
				// a hook is triggered that calls more driver methods.
				// Note: that there's no risk that another thread will submit a downstream update "out of order"
				// before ours (below) because this code runs on the ChangeReceiver thread, which is
				// the only thread that submits updates downstream.

				newDriver.onRevisionToSkip(loadedState.revision);
				publishFormatDriver(newDriver);

				// TODO: It's not clear we actually want loadedState.diagnosticAttributes here.
				// This causes downstream.submitReplacement to be associated with the last update to the state,
				// which is of dubious relevance. We might just want to use the context from the current thread,
				// which is probably empty because this runs on the ChangeReceiver thread.
				try (var ___ = bosk.rootReference().diagnosticContext().withOnly(loadedState.diagnosticAttributes)) {
					downstream.submitReplacement(bosk.rootReference(), loadedState.state);
				}
			} else {
				LOGGER.debug("Running initialRoot action");
				runInitialRootAction(initialRootAction);
				//TODO: Both branches of this "if" end with calls to onRevisionToSKip and publishFormatDriver.
				// Is there a way to rearrange the code so those calls can be in one place?
			}
		}

		private void runInitialRootAction(FutureTask<R> initialRootAction) throws InterruptedException, TimeoutException, InitialRootActionException {
			initialRootAction.run();
			try {
				initialRootAction.get(5 * driverSettings.recoveryPollingMS(), MILLISECONDS);
				LOGGER.debug("initialRoot action completed successfully");
			} catch (ExecutionException e) {
				LOGGER.debug("initialRoot action failed", e);
				throw new InitialRootActionException(e.getCause());
			}
		}

		@Override
		public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
			LOGGER.debug("onEvent({}:{})", event.getOperationType().getValue(), getDocumentKeyValue(event));
			LOGGER.trace("Event details: {}", event);
			formatDriver.onEvent(event);
		}

		private Object getDocumentKeyValue(ChangeStreamDocument<BsonDocument> event) {
			BsonDocument documentKey = event.getDocumentKey();
			if (documentKey == null) {
				return null;
			} else  {
				BsonValue value = documentKey.get("_id");
				if (value instanceof BsonString) {
					return value.asString().getValue();
				} else {
					return value;
				}
			}
		}

		@Override
		public void onConnectionFailed(Exception e) throws InterruptedException, InitialRootActionException, TimeoutException {
			LOGGER.debug("onConnectionFailed");
			FutureTask<R> initialRootAction = this.taskRef.get();
			if (initialRootAction == null) {
				LOGGER.debug("Nothing to do");
			} else {
				LOGGER.debug("Running initialRoot action");
				runInitialRootAction(initialRootAction);
			}

		}

		@Override
		public void onDisconnect(Throwable e) {
			LOGGER.debug("onDisconnect({})", e.toString());
			formatDriver.close();
			quietlySetFormatDriver(new DisconnectedDriver<>(e));
		}
	}

	private FormatDriver<R> newPreferredFormatDriver() {
		DatabaseFormat preferred = driverSettings.preferredDatabaseFormat();
		if (preferred.equals(SEQUOIA) || preferred instanceof PandoFormat) {
			return newSingleDocFormatDriver(REVISION_ZERO.longValue(), preferred);
		}
		throw new AssertionError("Unknown database format setting: " + preferred);
	}

	private FormatDriver<R> detectFormat() throws UninitializedCollectionException, UnrecognizedFormatException {
		Manifest manifest = Manifest.forSequoia();
		try (MongoCursor<BsonDocument> cursor = collection.find(new BsonDocument("_id", MANIFEST_ID)).cursor()) {
			if (cursor.hasNext()) {
				LOGGER.debug("Found manifest");
				manifest = formatter.decodeManifest(cursor.next());
			} else {
				// For legacy databases with no manifest
				LOGGER.debug("Manifest is missing; checking for Sequoia format");
			}
		}

		DatabaseFormat format = manifest.pando().isPresent()? manifest.pando().get() : SEQUOIA;
		BsonString documentId = (format == SEQUOIA)
			? SequoiaFormatDriver.DOCUMENT_ID
			: PandoFormatDriver.ROOT_DOCUMENT_ID;
		FindIterable<BsonDocument> result = collection.find(new BsonDocument("_id", documentId));
		try (MongoCursor<BsonDocument> cursor = result.cursor()) {
			if (cursor.hasNext()) {
				BsonInt64 revision = cursor
					.next()
					.getInt64(DocumentFields.revision.name(), REVISION_ZERO);
				return newSingleDocFormatDriver(revision.longValue(), format);
			} else {
				throw new UninitializedCollectionException("Document doesn't exist");
			}
		}
	}

	private FormatDriver<R> newSingleDocFormatDriver(long revisionAlreadySeen, DatabaseFormat format) {
		if (format.equals(SEQUOIA)) {
			return new SequoiaFormatDriver<>(
				bosk,
				collection,
				driverSettings,
				bsonPlugin,
				new FlushLock(driverSettings, revisionAlreadySeen),
				downstream);
		} else if (format instanceof PandoFormat) {
			return new PandoFormatDriver<>(
				bosk,
				collection,
				driverSettings,
				(PandoFormat) format,
				bsonPlugin,
				new FlushLock(driverSettings, revisionAlreadySeen),
				downstream);
		}
		throw new IllegalArgumentException("Unexpected database format: " + format);
	}


	private MDCScope beginDriverOperation(String description, Object... args) {
		if (isClosed) {
			throw new IllegalStateException("Driver is closed");
		}
		MDCScope ex = setupMDC(bosk.name());
		LOGGER.debug(description, args);
		if (driverSettings.testing().eventDelayMS() < 0) {
			LOGGER.debug("| eventDelayMS {}ms ", driverSettings.testing().eventDelayMS());
			try {
				Thread.sleep(-driverSettings.testing().eventDelayMS());
			} catch (InterruptedException e) {
				LOGGER.debug("Sleep interrupted", e);
			}
		}
		return ex;
	}

	private <X extends Exception, Y extends Exception> void doRetryableDriverOperation(RetryableOperation<X,Y> operation, String description, Object... args) throws X,Y {
		RetryableOperation<X,Y> operationInSession = () -> {
			try (var session = collection.newSession()) {
				operation.run();
				session.commitTransactionIfAny();
			} catch (FailedSessionException e) {
				quietlySetFormatDriver(new DisconnectedDriver<>(e));
				throw new DisconnectedException(e);
			}
		};
		try (var __ = beginDriverOperation(description, args)) {
			try {
				operationInSession.run();
			} catch (DisconnectedException e) {
				LOGGER.debug("Driver is disconnected ({}); will wait and retry operation", e.getMessage());
				waitAndRetry(operationInSession, description, args);
			}
		}
	}

	private <X extends Exception, Y extends Exception> void waitAndRetry(RetryableOperation<X, Y> operation, String description, Object... args) throws X, Y {
		try {
			formatDriverLock.lock();
			long waitTimeMS = 5 * driverSettings.recoveryPollingMS();
			LOGGER.debug("Waiting for new FormatDriver for {} ms", waitTimeMS);
			boolean success = formatDriverChanged.await(waitTimeMS, MILLISECONDS);
			if (!success) {
				LOGGER.warn("Timed out waiting for MongoDB to recover; will retry anyway, but the operation may fail");
			}
		} catch (InterruptedException e) {
			// In a library, it's hard to know what a user expects when interrupting a thread.
			// Usually they'd like to stop the current operation, but since most BoskDriver methods
			// don't throw InterruptedException, we have no clear way to report this to the application.
			//
			// We're going to assume the user would be satisfied if, instead of aborting,
			// the operation succeeded or failed immediately, and so we'll respond to the interruption
			// by retrying immediately. On failure, the exception thrown to the application
			// will be the natural one we would have thrown anyway; on success, the effect will be
			// as though it had succeeded on the first attempt and the retry hadn't been necessary.
			//
			// This is not ideal if the operation we're retrying is time-consuming,
			// because the interruption won't have the desired effect of stopping the operation,
			// and two interruptions would be required; but this seems like a decent compromise
			// until we devise something better. Hopefully this kind of behaviour is not too astonishing
			// to a user of a library that contains automatic retry logic.
			LOGGER.debug("Interrupted while waiting to retry; proceeding");
		} finally {
			formatDriverLock.unlock();
		}
		LOGGER.debug("Retrying " + description, args);
		operation.run();
	}

	/**
	 * Sets {@link #formatDriver} but does not signal threads waiting to retry,
	 * because there's very likely a better driver on its way.
	 */
	void quietlySetFormatDriver(FormatDriver<R> newFormatDriver) {
		LOGGER.debug("quietlySetFormatDriver({}) (was {})", newFormatDriver.getClass().getSimpleName(), formatDriver.getClass().getSimpleName());
		try {
			formatDriverLock.lock();
			formatDriver.close();
			formatDriver = newFormatDriver;
		} finally {
			formatDriverLock.unlock();
		}
	}

	/**
	 * Sets {@link #formatDriver} and also signals any threads waiting to retry.
	 */
	void publishFormatDriver(FormatDriver<R> newFormatDriver) {
		LOGGER.debug("publishFormatDriver({}) (was {})", newFormatDriver.getClass().getSimpleName(), formatDriver.getClass().getSimpleName());
		try {
			formatDriverLock.lock();
			formatDriver.close();
			formatDriver = newFormatDriver;
			LOGGER.debug("Signaling");
			formatDriverChanged.signalAll();
		} finally {
			formatDriverLock.unlock();
		}
	}

	private interface RetryableOperation<X extends Exception, Y extends Exception> {
		void run() throws X,Y;
	}

	public static final String COLLECTION_NAME = "boskCollection";
	public static final BsonString MANIFEST_ID = new BsonString("manifest");
	private static final Exception FAILURE_TO_COMPUTE_INITIAL_ROOT = new Exception("Failure to compute initial root");
	private static final Logger LOGGER = LoggerFactory.getLogger(MainDriver.class);
}
