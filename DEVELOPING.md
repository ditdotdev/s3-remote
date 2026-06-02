# Project Development

For general information about contributing changes, see the
[Contributor Guidelines](https://github.com/ditdotdev/.github/blob/master/CONTRIBUTING.md).

## How it Works

The provider uses the Dit `remote-sdk` to provide interfaces for
`dit-server` to use. The resulting client and server jars are incorporated
into the dit-server build.

## Building

Run `gradle build`.

## Testing

Tests are run as part of `gradle build`. Tests can also be explicitly run
via `gradle test`.

## Releasing

Releases are triggered by pushing tags to the master branch.
