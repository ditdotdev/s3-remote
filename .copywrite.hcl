schema_version = 1

project {
  license          = "BUSL-1.1"
  copyright_holder = "Dit"
  copyright_year   = 2026

  # Gradle wrapper files are Gradle's own (Apache-2.0); build artifacts and
  # coverage output must not carry license headers either.
  header_ignore = [
    "gradlew",
    "gradlew.bat",
    "gradle/**",
    "build/**",
    "**/build/**",
    ".health/**",
    "**/*.out",
  ]
}
