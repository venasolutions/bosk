package io.vena.bosk.drivers;

import io.vena.bosk.BindingEnvironment;
import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingEntry;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.annotations.Hook;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.Value;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class HanoiTest {
	protected Bosk<HanoiState> bosk;
	Refs refs;
	BlockingQueue<Integer> numSolved;
	protected DriverFactory<HanoiState> driverFactory;
	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	public interface Refs {
		@ReferencePath("/puzzles") CatalogReference<Puzzle> puzzles();
		@ReferencePath("/puzzles/-puzzle-") Reference<Puzzle> puzzle(Identifier puzzle);
		@ReferencePath("/solved") Reference<Listing<Puzzle>> solved();
		@ReferencePath("/solved/-puzzle-") Reference<ListingEntry> solved(Identifier puzzle);
		@ReferencePath("/puzzles/-puzzle-/startingTower") Reference<Reference<Tower>> startingTower(Identifier puzzle);
		@ReferencePath("/puzzles/-puzzle-/towers/-tower-") Reference<Tower> tower(Identifier puzzle, Identifier tower);
		@ReferencePath("/puzzles/-puzzle-/towers/-tower-/discs/-disc-") Reference<Disc> anyDisc();
		@ReferencePath("/puzzles/-puzzle-/towers/-tower-/discs/-disc-") Reference<Disc> disc(Identifier puzzle, Identifier tower, Identifier disc);
	}

	@BeforeEach
	void setup() throws InvalidTypeException {
		bosk = new Bosk<HanoiState>(
			"Hanoi" + boskCounter.incrementAndGet(),
			HanoiState.class,
			this::defaultRoot,
			driverFactory
		);
		refs = bosk.rootReference().buildReferences(Refs.class);
		numSolved = new LinkedBlockingDeque<>();
		bosk.registerHooks(this);
	}

	@ParametersByName
	void onePuzzle() throws InterruptedException {
		int numDiscs = 6;
		bosk.driver().submitReplacement(refs.puzzle(PUZZLE_1),
			newPuzzle(PUZZLE_1, numDiscs));
		while (numSolved.poll(1, MINUTES) != 1) {}
		try (var __ = bosk.readContext()) {
			assertEquals(new HanoiState(
				Catalog.of(solvedPuzzle(PUZZLE_1, numDiscs)),
				Listing.of(refs.puzzles(), PUZZLE_1)
			), bosk.rootReference().valueIfExists(),
				bosk.name() + " must match on " + currentThread().getName());
		}
	}

	@ParametersByName
	void threePuzzles() throws InterruptedException {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Registered hooks:{}",
				bosk.allRegisteredHooks().stream().map(r -> "\n\t" + r.name() + " @ " + r.scope()).collect(joining()));
		}
		bosk.driver().submitReplacement(refs.puzzles(),
			Catalog.of(
				newPuzzle(PUZZLE_1, 5),
				newPuzzle(PUZZLE_2, 3),
				newPuzzle(PUZZLE_3, 8)
			));
		while (numSolved.poll(1, MINUTES) != 3) {}
		try (var __ = bosk.readContext()) {
			HanoiState expected = new HanoiState(
				Catalog.of(
					solvedPuzzle(PUZZLE_1, 5),
					solvedPuzzle(PUZZLE_2, 3),
					solvedPuzzle(PUZZLE_3, 8)
				),
				Listing.of(refs.puzzles(), PUZZLE_2, PUZZLE_1, PUZZLE_3) // Solved in order of size
			);
			assertEquals(expected, bosk.rootReference().valueIfExists(),
				bosk.name() + " must match on " + currentThread().getName());
		}
	}

	@Hook("/solved")
	public void solveReportingHook(Reference<Listing<Puzzle>> ref) {
		int n = ref.value().size();
		LOGGER.debug("Reporting {} solved", n);
		numSolved.add(n);
	}

	@Hook("/puzzles/-puzzle-/towers/-tower-/discs/-disc-")
	public void discMoved(Reference<Disc> ref) {
		Disc moved = ref.valueIfExists();
		if (moved == null) {
			LOGGER.debug("Ignoring disc deletion: {}", ref);
			return;
		}
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("discMoved state:\n\t{}", bosk.rootReference().value());
		}
		BindingEnvironment env = refs.anyDisc().parametersFrom(ref.path());
		Identifier puzzle = env.get("puzzle");
		Identifier landingTowerID = env.get("tower");
		Reference<Tower> landingTower = refs.tower(puzzle, landingTowerID);
		Reference<Tower> t1 = refs.tower(puzzle, nextTower(landingTowerID));
		Tower tower1 = t1.value();
		Reference<Tower> t2 = refs.tower(puzzle, nextTower(tower1.id()));
		Tower tower2 = t2.value();
		LOGGER.trace("-- Landing={} t1={} t2={}", landingTowerID, tower1.id, tower2.id);

		// Rules:
		// 1. Never move the same disc twice
		// 2. If possible, move a disc "clockwise" to the nextTower

		if (tower1.discs().isEmpty()) {
			if (tower2.discs().isEmpty()) {
				if (refs.startingTower(puzzle).value().equals(landingTower)) {
					if (moved.size() == landingTower.value().topDisc().size()) {
						LOGGER.debug("First move from {} to empty {}", landingTowerID, t1);
						submitMove(landingTower.value(), tower1, landingTower.value().topDisc(), puzzle);
					} else {
						LOGGER.debug("Ignoring initial \"move\" of buried disc {}", moved);
					}
				} else {
					LOGGER.debug("Puzzle is solved");
					bosk.driver().submitReplacement(refs.solved(puzzle), LISTING_ENTRY);
				}
			} else {
				var disc2 = tower2.topDisc();
				if (disc2.size() < moved.size()) {
					LOGGER.debug("Move from t2 {} to landing tower", t2);
					submitMove(tower2, landingTower.value(), tower2.topDisc(), puzzle);
				} else {
					LOGGER.debug("Move from t2 {} to empty t1", t2);
					submitMove(tower2, tower1, tower2.topDisc(), puzzle);
				}
			}
		} else if (tower2.discs().isEmpty()) {
			LOGGER.debug("Move from non-empty t1 {}", t1);
			submitMove(tower1, tower2, tower1.topDisc(), puzzle);
		} else {
			var disc1 = tower1.topDisc();
			var disc2 = tower2.topDisc();
			if (disc1.size() < disc2.size()) {
				LOGGER.debug("Move smaller {} onto {}", t1, t2);
				submitMove(tower1, tower2, disc1, puzzle);
			} else if (disc2.size() < moved.size()) {
				LOGGER.debug("Move {} onto landing tower", t2);
				submitMove(tower2, landingTower.value(), disc2, puzzle);
			} else {
				LOGGER.debug("Move {} onto bigger {}", t2, t1);
				submitMove(tower2, tower1, disc2, puzzle);
			}
		}
	}

	private void submitMove(Tower from, Tower to, Disc disc, Identifier puzzle) {
		LOGGER.debug("-> Moving {}", disc.id());
		bosk.driver().submitDeletion(refs.disc(puzzle, from.id(), disc.id()));
		bosk.driver().submitReplacement(refs.disc(puzzle, to.id(), disc.id()), disc);
	}

	private Puzzle newPuzzle(Identifier id, int numDiscs) {
		return new Puzzle(
			id,
			Catalog.of(
				newTower(LEFT, numDiscs),
				newTower(MIDDLE, 0),
				newTower(RIGHT, 0)
			),
			refs.tower(id, LEFT)
		);
	}

	private Puzzle solvedPuzzle(Identifier id, int numDiscs) {
		if (numDiscs % 2 == 1) {
			// Odd towers end up on the middle
			return new Puzzle(
				id,
				Catalog.of(
					newTower(LEFT, 0),
					newTower(MIDDLE, numDiscs),
					newTower(RIGHT, 0)
				),
				refs.tower(id, LEFT)
			);
		} else {
			// Even towers end up on the right
			return new Puzzle(
				id,
				Catalog.of(
					newTower(LEFT, 0),
					newTower(MIDDLE, 0),
					newTower(RIGHT, numDiscs)
				),
				refs.tower(id, LEFT)
			);
		}
	}

	private Tower newTower(Identifier id, int numDiscs) {
		return new Tower(id, Catalog.of(
			IntStream.range(0, numDiscs)
				.map(i -> numDiscs - i)
				.mapToObj(Integer::toString)
				.map(Identifier::from)
				.map(Disc::new)
		));
	}

	private HanoiState defaultRoot(Bosk<HanoiState> bosk) throws InvalidTypeException {
		CatalogReference<Puzzle> puzzlesRef = bosk.rootReference().thenCatalog(Puzzle.class, "puzzles");
		return new HanoiState(
			Catalog.empty(),
			Listing.empty(puzzlesRef)
		);
	}

	@Value
	public static class HanoiState implements StateTreeNode {
		Catalog<Puzzle> puzzles;
		Listing<Puzzle> solved;
	}

	@Value
	public static class Puzzle implements Entity {
		Identifier id;
		Catalog<Tower> towers;
		Reference<Tower> startingTower;
	}

	@Value
	public static class Tower implements Entity {
		Identifier id;
		Catalog<Disc> discs;

		public Disc topDisc() {
			return discs.stream().reduce(null, (a,b) -> b);
		}

		@Override public String toString() { return discs.asCollection().toString(); }
	}

	@Value
	public static class Disc implements Entity {
		Identifier id;

		public int size() { return Integer.parseInt(id.toString()); }

		@Override public String toString() { return id.toString(); }
	}

	private Identifier nextTower(Identifier t) {
		if (LEFT.equals(t)) {
			return MIDDLE;
		} else if (MIDDLE.equals(t)) {
			return RIGHT;
		} else {
			return LEFT;
		}
	}

	private static final Identifier PUZZLE_1 = Identifier.from("p1");
	private static final Identifier PUZZLE_2 = Identifier.from("p2");
	private static final Identifier PUZZLE_3 = Identifier.from("p3");
	private static final Identifier LEFT    = Identifier.from("left");
	private static final Identifier MIDDLE  = Identifier.from("middle");
	private static final Identifier RIGHT   = Identifier.from("right");

	private static final Logger LOGGER = LoggerFactory.getLogger(HanoiTest.class);
}
