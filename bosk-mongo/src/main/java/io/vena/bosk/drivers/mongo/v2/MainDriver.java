package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.mongo.BsonPlugin;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.drivers.mongo.v2.Formatter.DocumentFields;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InitializationFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NotYetImplementedException;
import io.vena.bosk.exceptions.TunneledCheckedException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SINGLE_DOC;
import static io.vena.bosk.drivers.mongo.v2.Formatter.REVISION_ZERO;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Serves as a harness for driver implementations.
 * Handles resiliency concerns like exception handling, disconnecting, and reinitializing.
 * Handles {@link MDC} and some basic logging.
 * Implements format detection and refurbish operations.
 */
public class MainDriver<R extends Entity> implements MongoDriver<R> {
	private final Bosk<R> bosk;
	private final MongoDriverSettings driverSettings;
	private final BsonPlugin bsonPlugin;
	private final BoskDriver<R> downstream;

	private final Reference<R> rootRef;
	private final MongoClient mongoClient;
	private final MongoCollection<Document> collection;
	private final ChangeEventReceiver receiver;

	private final AtomicReference<FutureTask<R>> initializationInProgress = new AtomicReference<>();
	private volatile FormatDriver<R> formatDriver = new DisconnectedDriver<>("Driver not yet initialized");
	private volatile boolean isClosed = false;

	private final ScheduledExecutorService livenessPool = Executors.newScheduledThreadPool(1);

	public MainDriver(
		Bosk<R> bosk,
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin,
		BoskDriver<R> downstream
	) {
		validateMongoClientSettings(clientSettings);

		this.bosk = bosk;
		this.driverSettings = driverSettings;
		this.bsonPlugin = bsonPlugin;
		this.downstream = downstream;

		this.rootRef = bosk.rootReference();
		this.mongoClient = MongoClients.create(clientSettings);
		this.collection = mongoClient
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME);
		this.receiver = new ChangeEventReceiver(bosk.name(), driverSettings, collection);

		livenessPool.scheduleWithFixedDelay(
			this::backgroundReconnectTask,
			driverSettings.recoveryPollingMS(),
			driverSettings.recoveryPollingMS(),
			MILLISECONDS);
	}

	private void validateMongoClientSettings(MongoClientSettings clientSettings) {
		// We require ReadConcern and WriteConcern to be MAJORITY to ensure the Causal Consistency
		// guarantees needed to meet the requirements of the BoskDriver interface.
		// https://www.mongodb.com/docs/manual/core/causal-consistency-read-write-concerns/

		if (clientSettings.getReadConcern() != ReadConcern.MAJORITY) {
			throw new IllegalArgumentException("MongoDriver requires MongoClientSettings to specify ReadConcern.MAJORITY");
		}
		if (clientSettings.getWriteConcern() != WriteConcern.MAJORITY) {
			throw new IllegalArgumentException("MongoDriver requires MongoClientSettings to specify WriteConcern.MAJORITY");
		}
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		try (MDCScope __ = beginDriverOperation("initialRoot")) {
			R result;
			try {
				result = initializeReplication();
			} catch (UninitializedCollectionException e) {
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Creating collection", e);
				} else {
					LOGGER.info("Creating collection");
				}
				FormatDriver<R> newDriver = newPreferredFormatDriver();
				result = downstream.initialRoot(rootType);
				doInitialization(result, newDriver);
				formatDriver = newDriver;
			} catch (IOException | ReceiverInitializationException e) {
				LOGGER.debug("Unable to initialize replication", e);
				result = null;
			}
			if (receiver.isReady()) {
				receiver.start();
			} else {
				LOGGER.debug("Receiver not started");
			}
			if (result == null) {
				return downstream.initialRoot(rootType);
			} else {
				return result;
			}
		}
	}

	private FormatDriver<R> newPreferredFormatDriver() {
		if (driverSettings.preferredDatabaseFormat() == SINGLE_DOC) {
			return newSingleDocFormatDriver(REVISION_ZERO.longValue());
		} else {
			throw new AssertionError("Unknown database format setting: " + driverSettings.preferredDatabaseFormat());
		}
	}

	private void recoverFrom(Exception exception) {
		try (MDCScope __ = beginDriverOperation("recoverFrom({})", exception.getClass().getSimpleName())) {
			if (isClosed) {
				LOGGER.debug("Closed driver ignoring exception", exception);
				return;
			}
			if (exception instanceof DisconnectedException) {
				LOGGER.debug("Recovering from {}; reinitializing", exception.getClass().getSimpleName(), exception);
			} else {
				LOGGER.error("Recovering from unexpected {}; reinitializing", exception.getClass().getSimpleName(), exception);
			}
			recover();
		}
	}

	private void backgroundReconnectTask() {
		boolean isDisconnected = formatDriver instanceof DisconnectedDriver;
		if (isDisconnected) {
			try (MDCScope __ = beginDriverOperation("backgroundReconnectTask()")) {
				LOGGER.debug("Recovering from disconnection");
				try {
					recover();
				} catch (RuntimeException e) {
					LOGGER.debug("Error recovering from disconnection: {}", e.getClass().getSimpleName(), e);
					// Ignore and try again next time
				}
			}
		} else {
			LOGGER.trace("Nothing to do");
		}
	}

	private void recover() {
		R newRoot;
		try {
			newRoot = initializeReplication();
		} catch (UninitializedCollectionException e) {
			LOGGER.warn("Collection is uninitialized; driver is disconnected", e);
			return;
		} catch (IOException | ReceiverInitializationException e) {
			LOGGER.warn("Unable to initialize event receiver", e);
			return;
		}
		if (newRoot != null) {
			// Because we haven't called receiver.start() yet, this won't race with other events
			downstream.submitReplacement(rootRef, newRoot);
		}
		if (!isClosed) {
			receiver.start();
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		runWithRetry(() ->
			formatDriver.submitReplacement(target, newValue), "submitReplacement({})", target);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		runWithRetry(() ->
			formatDriver.submitConditionalReplacement(target, newValue, precondition, requiredValue), "submitConditionalReplacement({}, {} = {})", target, precondition, requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		runWithRetry(() ->
			formatDriver.submitInitialization(target, newValue), "submitInitialization({})", target);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		if (target.path().isEmpty()) {
			// TODO: Refactor this kind of error checking out of LocalDriver and make it available
			throw new IllegalArgumentException("Can't delete the root of the bosk");
		} else {
			runWithRetry(() ->
				formatDriver.submitDeletion(target), "submitDeletion({}, {})", target);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		runWithRetry(() ->
			formatDriver.submitConditionalDeletion(target, precondition, requiredValue), "submitConditionalDeletion({}, {} = {})", target, precondition, requiredValue);
	}

	@Override
	public void refurbish() throws IOException {
		runWithRetry(this::doRefurbish, "refurbish");
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (MDCScope __ = beginDriverOperation("flush")) {
			try {
				formatDriver.flush();
			} catch (FlushFailureException | RuntimeException e1) {
				recoverFrom(e1);
				LOGGER.debug("Retrying flush");
				try  {
					formatDriver.flush();
				} catch (DisconnectedException e2) { // Other RuntimeExceptions are unexpected
					// The message from DisconnectionException is suitable as-is
					throw new FlushFailureException(e2.getMessage(), e2);
				}
			}
		}
	}

	private void doInitialization(R result, FormatDriver<R> newDriver) throws InitializationFailureException {
		ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
			.causallyConsistent(true)
			.defaultTransactionOptions(TransactionOptions.builder()
				.writeConcern(WriteConcern.MAJORITY)
				.readConcern(ReadConcern.MAJORITY)
				.build())
			.build();
		try (ClientSession session = mongoClient.startSession(sessionOptions)) {
			try {
				newDriver.initializeCollection(new StateAndMetadata<>(result, REVISION_ZERO));
			} finally {
				if (session.hasActiveTransaction()) {
					session.abortTransaction();
				}
			}
		}
	}

	private void doRefurbish() throws IOException {
		ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
			.causallyConsistent(true)
			.defaultTransactionOptions(TransactionOptions.builder()
				.writeConcern(WriteConcern.MAJORITY)
				.readConcern(ReadConcern.MAJORITY)
				.build())
			.build();
		try (ClientSession session = mongoClient.startSession(sessionOptions)) {
			try {
				// Design note: this operation shouldn't do any special coordination with
				// the receiver/listener system, because other replicas won't.
				// That system needs to cope with a refurbish operations without any help.
				session.startTransaction();
				StateAndMetadata<R> result = formatDriver.loadAllState();
				FormatDriver<R> newFormatDriver = newPreferredFormatDriver();
				collection.deleteMany(new BsonDocument());
				newFormatDriver.initializeCollection(result);
				session.commitTransaction();
				formatDriver = newFormatDriver;
			} catch (UninitializedCollectionException e) {
				throw new IOException("Unable to refurbish uninitialized database collection", e);
			} finally {
				if (session.hasActiveTransaction()) {
					session.abortTransaction();
				}
			}
		}
	}

	private <X extends Exception> void runWithRetry(Action<X> action, String description, Object... args) throws X {
		try (MDCScope __ = beginDriverOperation(description, args)) {
			try {
				action.run();
			} catch (RuntimeException e) {
				recoverFrom(e);
				LOGGER.debug("Retrying " + description, args);
				action.run();
			}
		}
	}

	@Override
	public void close() {
		try (MDCScope __ = setupMDC(bosk.name())) {
			LOGGER.debug("close");
			isClosed = true;
			receiver.close();
			formatDriver.close();
		}
	}

	private interface Action<X extends Exception> {
		void run() throws X;
	}

	/**
	 * Reinitializes {@link #receiver}, detects the database format, instantiates
	 * the appropriate {@link FormatDriver}, and uses it to load the initial bosk state.
	 * <p>
	 * Caller is responsible for calling {@link #receiver}{@link ChangeEventReceiver#start() .start()}
	 * to kick off event processing. We don't do it here because some callers need to do other things
	 * after initialization but before any events arrive.
	 * <p>
	 * Does some intelligent debouncing if multiple calls happen in parallel.
	 *
	 * @return The new root object to use, if any
	 * @throws UninitializedCollectionException if the database or collection doesn't exist
	 */
	private R initializeReplication() throws UninitializedCollectionException, ReceiverInitializationException, IOException {
		if (isClosed) {
			LOGGER.debug("Don't initialize replication on closed driver");
			return null;
		}

		// To "debounce" this, we create a task object that will do the initialization
		// if required, but we actually only run one at a time. Redundant tasks will just
		// be discarded without ever having run.
		FutureTask<R> initTask = new FutureTask<>(() -> {
			LOGGER.debug("Initializing replication");
			try {
				formatDriver.close();
				formatDriver = new DisconnectedDriver<>("Driver initialization failed"); // Fallback in case initialization fails
				if (receiver.initialize(new Listener())) {
					FormatDriver<R> newDriver = detectFormat();
					StateAndMetadata<R> result = newDriver.loadAllState();
					newDriver.onRevisionToSkip(result.revision);
					formatDriver = newDriver;
					return result.state;
				} else {
					LOGGER.warn("Unable to fetch resume token; disconnecting");
					formatDriver = new DisconnectedDriver<>("Unable to fetch resume token");
					return null;
				}
			} catch (ReceiverInitializationException | IOException | RuntimeException e) {
				LOGGER.warn("Failed to initialize replication", e);
				formatDriver = new DisconnectedDriver<>(e.toString());
				throw new TunneledCheckedException(e);
			} finally {
				// Clearing the map entry here allows the next initialization task to be created
				// now that this one has completed
				initializationInProgress.set(null);
			}
		});

		// Use initializationInProgress to check for an existing task, and if there isn't
		// one, use the new one we just created.
		FutureTask<R> init = initializationInProgress.updateAndGet(x -> {
			if (x == null) {
				LOGGER.debug("Will perform initialization");
				return initTask;
			} else {
				LOGGER.debug("Will wait for initialization already underway");
				return x;
			}
		});

		// This either runs the task (if it's the new one we just created) or waits for the run in progress to finish.
		init.run();

		try {
			R result = init.get();
			LOGGER.debug("Initialization returned {}", (result==null)? "null" : "new root");
			return result;
		} catch (InterruptedException e) {
			LOGGER.debug("Initialization interrupted", e);
			throw new NotYetImplementedException(e);
		} catch (ExecutionException e) {
			LOGGER.debug("Initialization threw {}", e.getCause().getClass().getSimpleName(), e.getCause());
			// Unpacking the exception is super annoying
			if (e.getCause() instanceof UninitializedCollectionException) {
				throw (UninitializedCollectionException) e.getCause();
			} else if (e.getCause() instanceof TunneledCheckedException) {
				Exception cause = ((TunneledCheckedException) e.getCause()).getCause();
				if (cause instanceof UninitializedCollectionException) {
					throw (UninitializedCollectionException) cause;
				} else if (cause instanceof ReceiverInitializationException) {
					throw (ReceiverInitializationException)cause;
				} else if (cause instanceof IOException) {
					throw (IOException)cause;
				} else if (cause instanceof RuntimeException) {
					throw (RuntimeException)cause;
				} else {
					throw new NotYetImplementedException(cause);
				}
			} else {
				throw new NotYetImplementedException(e);
			}
		}
	}

	private final class Listener implements ChangeEventListener {
		/**
		 * Raise your hand if you want to think about the case where a listener keeps on processing
		 * events after an exception. Nobody? Ok, that's what I thought.
		 */
		volatile boolean isListening = true; // (volatile is probably overkill because all calls are on the same thread anyway)

		@Override
		public void onEvent(ChangeStreamDocument<Document> event) throws UnprocessableEventException {
			if (isListening) {
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("# EVENT: {} {}", event.getOperationType().getValue(), event);
				} else {
					LOGGER.debug("# EVENT: {}", event.getOperationType().getValue());
				}
				try {
					formatDriver.onEvent(event);
				} catch (RuntimeException e) {
					onException(e);
				}
			}
		}

		@Override
		public void onException(Exception e) {
			isListening = false;
			recoverFrom(e);
		}
	}

	private FormatDriver<R> detectFormat() throws UninitializedCollectionException, UnrecognizedFormatException {
		FindIterable<Document> result = collection.find(new BsonDocument("_id", SingleDocFormatDriver.DOCUMENT_ID));
		try (MongoCursor<Document> cursor = result.cursor()) {
			if (cursor.hasNext()) {
				Long revision = cursor
					.next()
					.get(DocumentFields.revision.name(), 0L);
				return newSingleDocFormatDriver(revision);
			} else {
				throw new UninitializedCollectionException("Document doesn't exist");
			}
		}
	}

	private SingleDocFormatDriver<R> newSingleDocFormatDriver(long revisionAlreadySeen) {
		return new SingleDocFormatDriver<>(
			bosk,
			collection,
			driverSettings,
			bsonPlugin,
			new FlushLock(driverSettings, revisionAlreadySeen),
			downstream);
	}

	private MDCScope beginDriverOperation(String description, Object... args) {
		if (isClosed) {
			throw new IllegalStateException("Driver is closed");
		}
		MDCScope ex = setupMDC(bosk.name());
		LOGGER.debug(description, args);
		return ex;
	}

	static MDCScope setupMDC(String boskName) {
		MDCScope result = new MDCScope();
		MDC.put(MDC_KEY, " [" + boskName + "]");
		return result;
	}

	/**
	 * This is like {@link org.slf4j.MDC.MDCCloseable} except instead of
	 * deleting the MDC entry at the end, it restores it to its prior value,
	 * which allows us to nest these.
	 *
	 * <p>
	 * Note that for a try block using one of these, the catch and finally
	 * blocks will run after {@link #close()} and won't have the context.
	 * You probably want to use this in a try block with no catch or finally clause.
	 */
	static final class MDCScope implements AutoCloseable {
		final String oldValue = MDC.get(MDC_KEY);
		@Override public void close() { MDC.put(MDC_KEY, oldValue); }
	}

	public static final String COLLECTION_NAME = "boskCollection";
	private static final Logger LOGGER = LoggerFactory.getLogger(MainDriver.class);
	private static final String MDC_KEY = "MongoDriver";
}
