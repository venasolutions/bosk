# Bosk
Control plane state management library

## Structure

The repo is structured as a collection of subprojects because we publish several separate libraries.
`bosk-core` is the main functionality, and then other packages like `bosk-mongo` and `bosk-gson`
provide integrations with other technologies.

The subprojects are listed in `settings.gradle`, and each has its own `README.md` describing what it is.

## Development

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
