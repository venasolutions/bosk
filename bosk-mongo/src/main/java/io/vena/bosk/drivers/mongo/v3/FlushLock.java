package io.vena.bosk.drivers.mongo.v3;

import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.drivers.mongo.v2.DisconnectedException;
import io.vena.bosk.exceptions.FlushFailureException;
import java.io.Closeable;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Value;
import org.bson.BsonInt64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.System.identityHashCode;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Implements waiting mechanism for revision numbers
 *
 * <h3>Evolution note</h3>
 * There is an important scenario that we ought to support:
 * <ol><li>
 *     Document/collection/database gets deleted
 * </li><li>
 *     A new bosk gets created (say, by restarting a server)
 * </li><li>
 *     The new bosk reinitializes the database
 * </li></ol>
 *
 * This situation is covered in <code>MongoDriverResiliencyTest</code>,
 * but actually we don't handle this perfectly because flush logic looks
 * at the revision number only. In the case where the database
 * is reinitialized with a different bosk state but the same revision number,
 * checking the revision number only is insufficient to determine that the
 * in-memory state is out of date. The test presently passes, presumably
 * because it's rescued by the change stream event describing the disruption,
 * but if that event were sufficiently delayed, the flush could incorrectly
 * conclude that it does not need to wait.
 *
 * <p>
 * One reasonable solution would be to include a UUID <code>epoch</code>
 * field alongside the revision number, and to have the flush logic check
 * both. It would conclude that there's no need to wait only if both the
 * <code>epoch</code> and <code>revision</code> fields match expectations.
 *
 * <p>
 * We have not yet implemented this logic because, at the time of writing,
 * the revision logic seems quite widespread, and we're hoping to encapsulate
 * it within the {@link FormatDriver}. Once that happens, we can easily evolve
 * that logic to include an epoch concept without touching other components.
 */
class FlushLock implements Closeable {
	private final MongoDriverSettings settings;
	private final Lock queueLock = new ReentrantLock();
	private final PriorityBlockingQueue<Waiter> queue = new PriorityBlockingQueue<>();
	private volatile long alreadySeen;
	private boolean isClosed;

	/**
	 * @param revisionAlreadySeen needs to be the exact revision from the database:
	 * too old, and we'll wait forever for intervening revisions that have already happened;
	 * too new, and we'll proceed immediately without waiting for revisions that haven't happened yet.
	 */
	public FlushLock(MongoDriverSettings settings, long revisionAlreadySeen) {
		LOGGER.debug("New flush lock at revision {} [{}]", revisionAlreadySeen, identityHashCode(this));
		this.settings = settings;
		this.alreadySeen = revisionAlreadySeen;
	}

	@Value
	private static class Waiter implements Comparable<Waiter> {
		long revision;
		Semaphore semaphore;

		@Override
		public int compareTo(Waiter other) {
			return Long.compare(revision, other.revision);
		}
	}

	void awaitRevision(BsonInt64 revision) throws InterruptedException, FlushFailureException {
		long revisionValue = revision.longValue();
		Semaphore semaphore = new Semaphore(0);
		long past;
		try {
			queueLock.lock();
			if (isClosed) {
				throw new DisconnectedException("FlushLock is closed");
			}
			queue.add(new Waiter(revisionValue, semaphore));
			past = alreadySeen;
		} finally {
			queueLock.unlock();
		}
		if (revisionValue > past) {
			LOGGER.debug("Awaiting revision {} > {} [{}]", revisionValue, past, identityHashCode(this));
			if (!semaphore.tryAcquire(settings.flushTimeoutMS(), MILLISECONDS)) {
				throw new FlushFailureException("Timed out waiting for revision " + revisionValue + " > " + alreadySeen);
			}
			if (isClosed) {
				// Can't simply return and pretend this worked
				throw new DisconnectedException("FlushLock was closed while waiting");
			}
			LOGGER.debug("Done awaiting revision {} [{}]", revisionValue, identityHashCode(this));
		} else {
			LOGGER.debug("Revision {} <= {} is in the past; don't wait [{}]", revisionValue, past, identityHashCode(this));
		}
	}

	/**
	 * Called after updates are sent downstream.
	 * @param revision can be null
	 */
	void finishedRevision(BsonInt64 revision) {
		if (revision == null) {
			return;
		}

		try {
			queueLock.lock();
			long revisionValue = revision.longValue();
			if (isClosed) {
				LOGGER.debug("Closed FlushLock ignoring revision {} [{}]", revisionValue, identityHashCode(this));
				return;
			}
			if (revisionValue <= alreadySeen) {
				LOGGER.debug("Note: revision did not advance: {} <= {} [{}]", revisionValue, alreadySeen, identityHashCode(this));
			}

			do {
				Waiter w = queue.peek();
				if (w == null || w.revision > revisionValue) {
					break;
				} else {
					Waiter removed = queue.remove();
					assert w == removed;
					w.semaphore.release();
				}
			} while (true);

			alreadySeen = revisionValue;
			LOGGER.debug("Finished {} [{}]", revisionValue, identityHashCode(this));
		} finally {
			queueLock.unlock();
		}
	}

	@Override
	public void close() {
		try {
			queueLock.lock();
			LOGGER.debug("Closing [{}]", identityHashCode(this));
			isClosed = true;
			Waiter w;
			while ((w = queue.poll()) != null) {
				w.semaphore.release();
			}
		} finally {
			queueLock.unlock();
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushLock.class);
}
