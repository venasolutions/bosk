package org.vena.bosk.drivers;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Entity;
import org.vena.bosk.Reference;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NotYetImplementedException;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.util.stream.Collectors.joining;

/**
 * Note that we're pretty easygoing about the file we're watching. If it doesn't exist,
 * or contains invalid content, we soldier on. The intent is that an invalid file doesn't
 * take down your whole application.
 */
public final class FileDriver<R extends Entity> implements BoskDriver<R> {
	private final Path file;
	private final Gson gson;
	private final Reference<R> rootRef;
	private final BoskDriver<R> downstream;
	private final ExecutorService ex = Executors.newFixedThreadPool(1);

	public FileDriver(Path file, Gson gson, Reference<R> rootRef, BoskDriver<R> downstream) {
		this.file = file;
		this.gson = gson;
		this.rootRef = rootRef;
		this.downstream = downstream;
		initiateEventProcessing();
	}

	private void initiateEventProcessing() {
		WatchService watchService;
		try {
			watchService = FileSystems.getDefault().newWatchService();
			Path dir = file.getParent();
			if (dir == null) {
				throw new IllegalArgumentException("File has no parent directory: " + file);
			}
			dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
		} catch (IOException e) {
			throw new NotYetImplementedException(e);
		}
		ex.submit(() -> {
			Thread.currentThread().setName(getClass().getSimpleName() + "-events");
			while (!ex.isShutdown()) {
				LOGGER.debug("Awaiting event");
				WatchKey key;
				try {
					key = watchService.take();

					// We actually don't care about the events;
					// whatever they are, we're going to re-read the file.
					boolean fileIsAffected = key.pollEvents().stream().anyMatch(event ->
							file.equals(event.context())); // TODO: This doesn't work!
					if (fileIsAffected) {
						try {
							LOGGER.debug("Reloading from file " + file);
							downstream.submitReplacement(rootRef, readFromFile());
						} catch (Throwable e) {
							String description = key.pollEvents().stream()
									.map(event -> event.kind() + "(" + event.context() + ")")
									.collect(joining(","));
							LOGGER.error("Unable to process event: " + description, e);
							// TODO: How to handle this? For now, just keep soldiering on
						} finally {
							if (!key.reset()) {
								LOGGER.warn("Directory is no longer accessible; shutting down FileDriver");
								ex.shutdown();
							}
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					LOGGER.debug("Interrupted", e);
				}
			}
		});
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException {
		try {
			return readFromFile();
		} catch (IOException | JsonParseException e) {
			LOGGER.info("Error reading initial root from file; deferring to downstream driver", e);
			return downstream.initialRoot(rootType);
		}
	}

	@Override
	public void flush() throws InterruptedException, IOException {
		downstream.submitReplacement(rootRef, readFromFile());
		downstream.flush();
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<String> precondition, String requiredValue) {
		updatesNotSupported();
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<String> precondition, String requiredValue) {
		updatesNotSupported();
	}

	private R readFromFile() throws IOException, JsonParseException {
		try (Reader reader = Files.newBufferedReader(file)) {
			R result = gson.fromJson(reader, rootRef.targetType());
			if (result == null) {
				// This can happen if the file is blank
				throw new JsonParseException("File does not contain " + rootRef.targetType() + ": " + file);
			} else {
				return result;
			}
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		updatesNotSupported();
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		updatesNotSupported();
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		updatesNotSupported();
	}

	private void updatesNotSupported() {
		throw new IllegalArgumentException(getClass().getSimpleName() + " does not support updates");
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FileDriver.class);
}
