# Project Development

For general information about contributing changes, see the
[Contributor Guidelines](https://github.com/datadatdat/.github/blob/master/CONTRIBUTING.md).

## How it Works

The provider uses the Datadatdat `remote-sdk` to provide interfaces for
`datadatdat-server` to use. The resulting client and server jars are incorporated
into the datadatdat-server build.

## Building

Run `gradle build`.

## Testing

Tests are run as part of `gradle build`. Tests can also be explicitly run
via `gradle test`.

## Releasing

Releases are triggered by pushing tags to the master branch.
