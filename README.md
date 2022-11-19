# Hokkaido Packaging and Distribution plugin

This project is a Gradle plugin for packaging of JVM applications for
distribution on the desktop operating systems Linux, Windows and macOS.
It is intended to work for all kinds of applications and UI toolkits,
however focus is currently put on
[Compose for Desktop](https://github.com/JetBrains/compose-jb) as a UI
toolkit.

Hokkaido is based on the Compose plugin developed by Jetbrains that lives in
the [JetBrains/compose-jb](https://github.com/JetBrains/compose-jb)
repository.
We found a bunch of shortcomings while working on some Compose-based
applications and decided to create a fork detached from the rest of the
Compose project. It still relies on the Compose plugin for compiling etc.
but introduces its own Gradle tasks for packaging.

Notable differences:

* **Cross-platform packaging.** It is our plan to support packaging at least
  from Linux-based host to target all three desktop operating systems and
  their variants.
  It's looking promising so far and it's possible we might also be able to
  implement this ability for all three host systems.
  Focus is to implement this for Linux first as this is the most-convenient
  OS that runs on our CI servers anyway and we'd like them to build our
  packages for all systems.
  The original Compose plugin is based on JPackage that ships with the JDK
  since version 14. Cross compilation
  [is a non-goal](https://openjdk.org/jeps/343#Non-Goals) for JPackage,
  so it doesn't seem likely to change in the near future towards support for
  this.
* **Improved customization.** While packaing our first applications, we
  noticed some things were not customizable, such as
  [the images used in the Windows installers](https://wixtoolset.org/docs/v3/wixui/wixui_customizations/#replacing-the-default-bitmaps)
  or some specific parameters for the start menu shortcuts like adding an
  [AUMID](https://learn.microsoft.com/en-us/windows/win32/shell/appids).
  Some of those features are not supported by the Compose plugin, others
  are not even supported by JPackage. Proposing changes for JPackage is
  probably not going to yield quick results.
  Hence the work that JPackage usually does is
  mostly replaced by custom code that emulates its behavior and where
  relevant improves on it and offers better configurability.

## Development and debugging notes

See `ComposeSystemProperties.kt` for flags and switches that can be passed
to the plugin via Gradle project properties (`-P` flags).

## Notes from the original plugin

JetBrains Compose gradle plugin for easy configuration

Environment variables:
* `COMPOSE_GRADLE_PLUGIN_VERSION` - version of plugin
* `COMPOSE_GRADLE_PLUGIN_COMPOSE_VERSION` - version of JetBrains Compose used by the plugin
