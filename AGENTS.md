# JasperReports 6.21.6 — CVE-2026-6009 backport

Fork of Jaspersoft/jasperreports. Published as `io.github.audichuang:jasperreports:6.21.6`.
Not affiliated with Jaspersoft. Release steps: DEPLOYING.md.

## The one rule this fork exists for

The baseline is 6.21.5, not 7.x. Upstream fixed CVE-2026-6009 in 7.0.4/7.0.7, but 7.x is a
Jakarta rewrite that breaks existing .jasper files. Only the fix was ported.

Do not port other 7.x behaviour, even when it looks like an improvement — 7.0.4's
byte-count limit defaults to disabled, and 7.0.8's "do not initialize classes before
instantiation" changes existing behaviour. Success means: nothing changed except the hole.

## Never change these

- **Java package `net.sf.jasperreports.*`** — package names are written into the
  serialization stream, so renaming voids every existing .jasper file. The Maven
  groupId is a separate thing and was changed; the package was not.
- **`Bundle-SymbolicName`** — OSGi identity.
- **The fork must stay public and `<scm>` must point at it** — LGPL requires the
  corresponding source of a distributed modification.

## Building — every one of these bites

Run Maven from `jasperreports/`, not the repo root.

    rm -rf target ext/*/target tools/*/target
    mvn -f pom-all.xml package -Denforcer.skip=true

- **`-f pom-all.xml` is required.** `jasperreports-metadata` follows `${revision}`, so a
  6.21.6 build of it only exists inside the reactor.
- **`-Denforcer.skip=true` is required.** The pom demands JDK [1.8,9); upstream released
  6.21.5 from JDK 17 itself (see Build-Jdk in its MANIFEST). Target stays 1.8.
- **The manual `rm -rf` is required.** `mvn clean` is a no-op (`maven.clean.skip=true`),
  and `ext/fonts` collects `**/*.ttf` from the module root — which contains `target/`.
  Rebuilding without clearing nests the font jar one level deeper each time, growing it
  from 35 entries to hundreds. **The build succeeds silently either way.** CI is safe
  because it always starts from a fresh checkout.

## After touching the deserialization whitelist

- Upstream splits its whitelist across each module's `jasperreports_extension.properties`;
  `core/default.jasperreports.properties` alone is not the full picture. That is how the
  JFreeChart entries were missed.
- Abstract types need a trailing wildcard. A stream carries `Ellipse2D$Double`, never
  `Ellipse2D`, so an exact entry never matches.
- Re-run the four tools in `tests/cve-2026-6009/` — usage is in each file's header.
  `FieldAudit` is the systematic one: it walks every Serializable field type and is what
  finds a JasperReports class holding a third-party object.

## When comparing output across versions

`print.jrprint` and OOXML shape ids are non-deterministic **in upstream too** — HashMap
iteration order and `element.hashCode()` respectively. Always run a control (the same jar
twice) before calling a difference a regression. `tests/cve-2026-6009/xcheck/` does this.
