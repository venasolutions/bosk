package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.exceptions.NotYetImplementedException;
import java.io.Closeable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.var;
import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Has three jobs:
 *
 * <ol><li>
 *     does inversion of control on the change stream cursor,
 *     calling {@link ChangeEventListener} callback methods on a background thread,
 * </li><li>
 *     catches event-processing exceptions and reports them to {@link ChangeEventListener#onException}
 *     so the listener can initiate a clean reinitialization and recovery sequence, and
 * </li><li>
 *     acts as a long-lived container for the various transient objects ({@link MongoChangeStreamCursor},
 *     {@link ChangeEventListener}) that get replaced during reinitialization.
 * </li></ol>
 *
 */
@RequiredArgsConstructor
class ChangeEventReceiver implements Closeable {
	private final String boskName;
	private final MongoCollection<Document> collection;
	private final ExecutorService ex = Executors.newFixedThreadPool(1);

	private final Lock lock = new ReentrantLock();
	private volatile Session currentSession;
	private volatile BsonDocument lastProcessedResumeToken;
	private volatile Future<?> eventProcessingTask;

	@AllArgsConstructor
	private static final class Session {
		final MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;
		final ChangeEventListener listener;
		ChangeStreamDocument<Document> initialEvent;
	}

	/**
	 * Sets up an event processing loop so that it will start feeding events to
	 * <code>listener</code> when {@link #start()} is called.
	 * No events will be sent to <code>listener</code> before {@link #start()} has been called.
	 *
	 * <p>
	 * Shuts down the existing event processing loop, if any:
	 * this method has been specifically designed to be called more than once,
	 * in case you're wondering why we wouldn't just do this in the constructor.
	 * This method is also designed to support being called on the event-processing
	 * thread itself, since a re-initialization could be triggered by an event or exception.
	 * For example, a {@link ChangeEventListener#onException} implementation can call this.
	 *
	 * @return true if we obtained a resume token.
	 * @see #start()
	 */
	public boolean initialize(ChangeEventListener listener) throws ReceiverInitializationException {
		LOGGER.debug("Initializing receiver");
		try {
			lock.lock();
			stop();
			setupNewSession(listener);
			return lastProcessedResumeToken != null;
		} catch (RuntimeException | InterruptedException | TimeoutException e) {
			throw new ReceiverInitializationException(e);
		} finally {
			lock.unlock();
		}
	}

	public boolean isReady() {
		return currentSession != null;
	}

	public void start() {
		try {
			lock.lock();
			if (currentSession == null) {
				throw new IllegalStateException("Receiver is not initialized");
			}
			if (eventProcessingTask == null) {
				eventProcessingTask = ex.submit(() -> eventProcessingLoop(currentSession));
			} else {
				LOGGER.debug("Already running");
			}
		} finally {
			lock.unlock();
		}
	}

	public void stop() throws InterruptedException, TimeoutException {
		try {
			lock.lock();
			Future<?> task = this.eventProcessingTask;
			if (task != null) {
				LOGGER.debug("Canceling event processing task");
				task.cancel(true);
				try {
					task.get(10, SECONDS); // TODO: Config
					LOGGER.warn("Normal completion of event processing task was not expected");
				} catch (CancellationException e) {
					LOGGER.debug("Cancellation succeeded; event loop interrupted");
					this.eventProcessingTask = null;
				}
			}
		} catch (ExecutionException e) {
			throw new NotYetImplementedException("Event processing loop isn't supposed to throw!", e);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void close() {
		try {
			stop();
		} catch (TimeoutException | InterruptedException e) {
			LOGGER.info("Ignoring exception while closing ChangeEventReceiver", e);
		}
		ex.shutdown();
	}

	private void setupNewSession(ChangeEventListener newListener) {
		assert this.eventProcessingTask == null;
		LOGGER.debug("Setup new session");
		this.currentSession = null; // In case any exceptions happen during this method

		for (int attempt = 1; attempt <= 2; attempt++) {
			LOGGER.debug("Attempt #{}", attempt);
			ChangeStreamDocument<Document> initialEvent;
			BsonDocument resumePoint = lastProcessedResumeToken;
			if (resumePoint == null) {
				LOGGER.debug("Acquire initial resume token");
				// TODO: Config
				// Note: on a quiescent collection, tryNext() will wait for the Await Time to elapse, so keep it short
				try (var initialCursor = collection.watch().maxAwaitTime(20, MILLISECONDS).cursor()) {
					initialEvent = initialCursor.tryNext();
					if (initialEvent == null) {
						// In this case, tryNext() has caused the cursor to point to
						// a token in the past, so we can reliably use that.
						resumePoint = requireNonNull(initialCursor.getResumeToken(),
							"Cannot proceed without an initial resume token");
						lastProcessedResumeToken = resumePoint;
					} else {
						LOGGER.debug("Received event while acquiring initial resume token; storing it for processing in event loop");
						resumePoint = initialEvent.getResumeToken();
					}
				}
			} else {
				LOGGER.debug("Use existing resume token");
				initialEvent = null;
			}
			try {
				MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor
					= collection.watch().resumeAfter(resumePoint).cursor();
				currentSession = new Session(cursor, newListener, initialEvent);
				return;
			} catch (MongoCommandException e) {
				LOGGER.error("Change stream cursor command failed; discarding resume token", e);
				lastProcessedResumeToken = null;
				// If we haven't already retried, we'll continue around the loop
			}
		}
	}

	/**
	 * This method has no uncaught exceptions. They're all reported to {@link ChangeEventListener#onException}.
	 */
	private void eventProcessingLoop(Session session) {
		String oldThreadName = currentThread().getName();
		currentThread().setName(getClass().getSimpleName() + " [" + boskName + "]");
		try {
			if (session.initialEvent != null) {
				processEvent(session, session.initialEvent);
				session.initialEvent = null; // Allow GC
			}
			while (true) {
				processEvent(session, session.cursor.next());
			}
		} catch (MongoInterruptedException e) {
			// This happens when stop() cancels the task; this is part of normal operation
			LOGGER.debug("Event loop interrupted", e);
			session.listener.onException(e);
		} catch (RuntimeException e) {
			LOGGER.warn("Unexpected exception while processing events; event loop aborted", e);
			session.listener.onException(e);
		} finally {
			currentThread().setName(oldThreadName);
		}
	}

	private void processEvent(Session session, ChangeStreamDocument<Document> event) {
		session.listener.onEvent(event);
		lastProcessedResumeToken = event.getResumeToken();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ChangeEventReceiver.class);
}
