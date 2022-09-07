# Bosk
Bosk is a state management library for developing distributed control-plane logic.
It's a bit like server-side Redux for Java, but without the boilerplate code.
(No selectors, no action objects, no reducers.)

Bosk fosters a programming style that minimizes the surprises encountered
when deploying multiple copies of an application as a replica set
by encouraging reactive event-triggered closed-loop control logic
based on a user-defined immutable state tree structure,
and favouring idempotency and determinism.

State is kept in memory, making reads extremely fast (on the order of 50ns).
Replication is achieved by activating an optional MongoDB module, meaning the hard work of
change propagation, ordering, durability, consistency, atomicity, and observability,
as well as fault tolerance, and emergency manual state inspection and modification,
is all delegated to MongoDB: a well-known, reliable, battle-hardened codebase.
You don't need to trust Bosk to get all these details right:
all we do is maintain the in-memory replica by following the MongoDB change stream.

## Usage

The `bosk-core` library is enough to get started.
You can create a `Bosk` object and start writing your application.

Then you can add in other packages as you need them,
like `bosk-gson` for JSON serialization
or `bosk-mongo` for persistence and replication.
Use the same version number for all packages.

## Development

### Code Structure

The repo is structured as a collection of subprojects because we publish several separate libraries.
`bosk-core` is the main functionality, and then other packages like `bosk-mongo` and `bosk-gson`
provide integrations with other technologies.

The subprojects are listed in `settings.gradle`, and each has its own `README.md` describing what it is.

### Maven publishing

During development, set `version` in `build.gradle` to end with `-SNAPSHOT` suffix.
When you're ready to make a release, just delete the `-SNAPSHOT`.
The next commit should then bump the version number and re-add the `-SNAPSHOT` suffix
to begin development of the next version.

If you'd like to publish a snapshot version, you can comment out this code from `Jenkinsfile` like so:

```groovy
    stage('Publish to Artifactory') {
//      when {
//          branch 'develop'
//      }
```

### Versioning

In the long run, we'll use the usual semantic versioning.

For the 0.x.y releases, treat x as a manjor release number.

For the 0.0.x releases, all bets are off, and no backward compatibility is guaranteed.
