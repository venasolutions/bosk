package io.vena.bosk.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vena.bosk.Bosk;
import io.vena.bosk.Entity;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.SerializationPlugin;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NonexistentReferenceException;
import io.vena.bosk.jackson.JacksonPlugin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("${bosk.web.service-path}")
public class ServiceEndpoints {
	private final Bosk<?> bosk;
	private final ObjectMapper mapper;
	private final JacksonPlugin plugin;
	private final int prefixLength;

	public ServiceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonPlugin plugin,
		@Value("${bosk.web.service-path}") String contextPath
	) {
		this.bosk = bosk;
		this.mapper = mapper;
		this.plugin = plugin;
		this.prefixLength = contextPath.length();
	}

	@GetMapping(path = "/**", produces = MediaType.APPLICATION_JSON_VALUE)
	Object getAny(HttpServletRequest req) {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		Reference<Object> ref = referenceForPath(req);
		try {
			return ref.value();
		} catch (NonexistentReferenceException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Object does not exist: " + ref, e);
		}
	}

	@PutMapping(path = "/**")
	void putAny(HttpServletRequest req, HttpServletResponse rsp) throws IOException, InvalidTypeException {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		Reference<Object> ref = referenceForPath(req);
		Object newValue;
		try (@SuppressWarnings("unused") SerializationPlugin.DeserializationScope scope = plugin.newDeserializationScope(ref)) {
			newValue = mapper
				.readerFor(mapper.constructType(ref.targetType()))
				.readValue(req.getReader());
		}
		checkForMismatchedID(ref, newValue);
		discriminatePreconditionCases(req, new PreconditionDiscriminator() {
			@Override
			public void ifUnconditional() {
				bosk.driver().submitReplacement(ref, newValue);
			}

			@Override
			public void ifMustMatch(Identifier expectedRevision) {
				bosk.driver().submitConditionalReplacement(
					ref, newValue,
					revisionRef(ref), expectedRevision);
			}

			@Override
			public void ifMustNotExist() {
				bosk.driver().submitInitialization(ref, newValue);
			}
		});
		rsp.setStatus(HttpStatus.ACCEPTED.value());
	}

	@DeleteMapping(path = "/**")
	void deleteAny(HttpServletRequest req, HttpServletResponse rsp) {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		Reference<Object> ref = referenceForPath(req);
		discriminatePreconditionCases(req, new PreconditionDiscriminator() {
			@Override
			public void ifUnconditional() {
				bosk.driver().submitDeletion(ref);
			}

			@Override
			public void ifMustMatch(Identifier expectedRevision) {
				bosk.driver().submitConditionalDeletion(
					ref, revisionRef(ref), expectedRevision);
			}

			@Override
			public void ifMustNotExist() {
				// Request to delete a nonexistent object: nothing to do
			}
		});
		rsp.setStatus(HttpStatus.ACCEPTED.value());
	}

	private Reference<Object> referenceForPath(HttpServletRequest req) {
		String path = req.getServletPath();
		String boskPath = path.substring(prefixLength);
		if (boskPath.isBlank()) {
			boskPath = "/";
		}
		try {
			return bosk.reference(Object.class, Path.parse(boskPath));
		} catch (InvalidTypeException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid path: " + path, e);
		}
	}

	private Reference<Identifier> revisionRef(Reference<?> ref) {
		try {
			return ref.then(Identifier.class, "revision");
		} catch (InvalidTypeException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preconditions not supported for object with no  suitable revision field: " + ref, e);
		}
	}

	private interface PreconditionDiscriminator {
		void ifUnconditional();

		void ifMustMatch(Identifier expectedRevision);

		void ifMustNotExist();
	}

	/**
	 * ETags are a little fiddly to decode. This logic handles the various cases and error conditions,
	 * and then calls the given <code>discriminator</code> to perform the desired action.
	 */
	static void discriminatePreconditionCases(HttpServletRequest req, PreconditionDiscriminator discriminator) {
		String ifMatch = req.getHeader("If-Match");
		String ifNoneMatch = req.getHeader("If-None-Match");
		LOGGER.debug("| If-Match: {} -- If-None-Match: {}", ifMatch, ifNoneMatch);
		if (ifMatch == null) {
			if (ifNoneMatch == null) {
				LOGGER.trace("| Unconditional");
				discriminator.ifUnconditional();
			} else if ("*".equals(ifNoneMatch)) {
				LOGGER.trace("| MustNotExist");
				discriminator.ifMustNotExist();
			} else {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-None-Match header, if supplied, must be \"*\"");
			}
		} else if (ifNoneMatch == null) {
			Identifier expectedRevision = Identifier.from(etagStringValue(ifMatch));
			LOGGER.trace("| MustMatch({})", expectedRevision);
			discriminator.ifMustMatch(expectedRevision);
		} else {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot supply both If-Match and If-None-Match");
		}
	}

	private static String etagStringValue(String etagString) {
		if (etagString.length() < 3 || etagString.charAt(0) != '"' || etagString.charAt(etagString.length() - 1) != '"') {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ETag string must be a non-empty string surrounded by quotes: " + etagString);
		}
		String value = etagString.substring(1, etagString.length() - 1);
		// We permit only the ASCII subset of https://datatracker.ietf.org/doc/html/rfc7232#section-2.3
		for (int i = 0; i < value.length(); i++) {
			int ch = value.codePointAt(i);
			if (ch == '"') {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a single ETag string is supported: " + etagString);
			} else if (ch == 0x21 || (0x23 <= ch && ch <= 0x7E)) { // Note: 0x22 is the quote character
				// all is well
			} else {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ETag string contains an unsupported character at position " + i + ", code point " + ch + ": " + etagString);
			}
		}
		return value;
	}

	private void checkForMismatchedID(Reference<Object> ref, Object newValue) throws InvalidTypeException {
		if (newValue instanceof Entity && !ref.path().isEmpty()) {
			Reference<?> enclosingRef = ref.enclosingReference(Object.class);
			if (EnumerableByIdentifier.class.isAssignableFrom(enclosingRef.targetClass())) {
				Identifier pathID = Identifier.from(ref.path().lastSegment());
				Identifier entityID = ((Entity) newValue).id();
				if (!pathID.equals(entityID)) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ref.getClass().getSimpleName() + " ID \"" + entityID + "\" does not match path ID \"" + pathID + "\"");
				}
			}
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceEndpoints.class);
}
