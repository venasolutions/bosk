# bosk
Control plane state management library

## Development

### Maven publishing

During development, set `version` in `build.gradle` to end with `-SNAPSHOT` suffix.
When you're ready to make a release, just delete the `-SNAPSHOT`.
The next commit should then bump the version number and re-add the `-SNAPSHOT` suffix
to begin development of the next version.
