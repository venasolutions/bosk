# Bosk

![Three inquisitive cartoon trees with eyes](/art/bosk-3trees.png)

Bosk is a state management library for developing distributed control-plane logic.
It's a bit like server-side Redux for Java, but without the boilerplate code.
(No selectors, no action objects, no reducers.)

Bosk eases the transition from a standalone application to a clustered high-availability replica set,
by supporting a programming style that minimizes the surprises encountered during the transition.
Bosk encourages reactive event-triggered closed-loop control logic
based on a user-defined immutable state tree structure,
and favours idempotency and determinism.

State is kept in memory, making reads extremely fast (on the order of 50ns).
Replication is achieved by activating an optional [MongoDB module](bosk-mongo), meaning the hard work of
change propagation, ordering, durability, consistency, atomicity, and observability,
as well as fault tolerance, and emergency manual state inspection and modification,
is all delegated to MongoDB: a well-known, reliable, battle-hardened codebase.
You don't need to trust Bosk to get all these details right:
all we do is send updates to MongoDB, and maintain the in-memory replica by following the MongoDB change stream.

## Usage

The [bosk-core](bosk-core) library is enough to get started.
You can create a `Bosk` object and start writing your application.

Add in other packages as you need them,
like [bosk-gson](bosk-gson) for JSON serialization
or [bosk-mongo](bosk-mongo) for persistence and replication.
Use the same version number for all packages.

### Compiler flags

Ensure `javac` is supplied the `-parameters` flag.

This is required because,
for each class you use to describe your Bosk state, the "system of record" for its structure is its constructor.
For example, you might define a class with a constructor like this:

```
public Member(Identifier id, String name) {...}
```

Based on this, Bosk now knows the names and types of all the "properties" of your object.
For this to work smoothly, the parameter names must be present in the compiled bytecode.

## Development

### Code Structure

The repo is structured as a collection of subprojects because we publish several separate libraries.
[bosk-core](bosk-core) is the main functionality, and then other packages like [bosk-mongo](bosk-mongo) and [bosk-gson](bosk-mongo)
provide integrations with other technologies.

The subprojects are listed in [settings.gradle](settings.gradle), and each has its own `README.md` describing what it is.

### Gradle setup

Each project has its own `build.gradle`.
Common settings across projects are in custom plugins under the [buildSrc directory](buildSrc/src/main/groovy).

### Versioning

In the long run, we'll use the usual semantic versioning.

For the 0.x.y releases, treat x as a manjor release number.

For the 0.0.x releases, all bets are off, and no backward compatibility is guaranteed.

### Logo

Logo was derived from this public-domain image: https://openclipart.org/detail/44023/small-trees-bushes

Modified by Grady Johnson.
