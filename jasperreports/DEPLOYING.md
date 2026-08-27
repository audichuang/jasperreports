# Publishing this fork to Maven Central

Coordinates: `io.github.audichuang:jasperreports:6.21.6` (plus the ext/tools modules).

## Before you start — three things that cannot be undone

1. **Central is append-only.** Once `6.21.6` is published it can never be deleted or
   overwritten. A mistake means burning the version number and publishing `6.21.7`.
2. **The name is public.** The artifact identifies itself as "JasperReports Library"
   because that is what it is. `name`, `description` and `url` state plainly that this
   is an unofficial rebuild not affiliated with Jaspersoft / Cloud Software Group.
   Keep that wording — it is what keeps the listing honest.
3. **LGPL obligations apply on distribution.** Publishing a modified LGPL library
   means the corresponding source must be available. The GitHub fork satisfies this,
   so keep the repository public and keep `<scm>` pointing at it.

## One-time setup

### 1. Central Portal account and namespace

Sign in at <https://central.sonatype.com> with the GitHub account `audichuang`.
Register the namespace `io.github.audichuang`. Because it is an `io.github.*`
namespace, verification is done by creating a temporary public repository whose
name is the verification code the Portal gives you.

### 2. Publishing token

In the Portal: **Account → Generate User Token**. It returns a username/password
pair. Put it in `~/.m2/settings.xml` under the server id the pom already expects:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

The id must be `central` — it matches `<publishingServerId>` in `pom-parent.xml`.

### 3. GPG signing key

Central requires a detached signature for every artifact.

```bash
gpg --gen-key                          # RSA 4096, no expiry is fine
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

The public key must be on a keyserver before publishing, or validation fails.
If the key has a passphrase, add it to `settings.xml`:

```xml
<profiles>
  <profile>
    <id>gpg</id>
    <activation><activeByDefault>true</activeByDefault></activation>
    <properties>
      <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
      <gpg.passphrase>YOUR_PASSPHRASE</gpg.passphrase>
    </properties>
  </profile>
</profiles>
```

## Publishing

Commit everything first — `buildnumber-maven-plugin` refuses a dirty tree, and the
commit hash is what ends up in `Implementation-Version`.

```bash
cd jasperreports
git status --porcelain          # must be empty

# MANDATORY - see below. `mvn clean` does nothing here.
rm -rf target ext/*/target tools/*/target

mvn -f pom-all.xml deploy -Prelease -Denforcer.skip=true
```

### Why the manual clean is mandatory

`pom-all.xml` sets `maven.clean.skip=true`, so `mvn clean` is a no-op and `target/`
accumulates across builds. That is normally harmless, but `ext/fonts/pom.xml`
declares

```xml
<resource>
  <directory>./</directory>          <!-- the module root, which contains target/ -->
  <includes><include>**/*.ttf</include>
```

so every rebuild copies the previous `target/classes` back into itself one level
deeper. After six local builds the fonts jar had grown from 35 files / 9 MB to
215 files / 65 MB, with paths like
`target/classes/target/classes/.../DejaVuSans.ttf`.

**The build succeeds either way** — nothing warns you. Upstream never hits this
because CI builds from a fresh checkout. Always verify before publishing:

```bash
unzip -l ext/fonts/target/jasperreports-fonts-6.21.6.jar | tail -3   # expect 35 files
```

### About jasperreports-fonts having no sources/javadoc

Its pom sets `maven.source.skip` and `maven.javadoc.skip` — it contains no Java
source, only font files. Upstream publishes it the same way: 6.21.5 on Central has
only `.jar`, `.pom` and their `.asc` signatures. This is expected, not a gap.

`-Prelease` adds the `-sources.jar`, `-javadoc.jar` and GPG signatures Central
requires. `-Denforcer.skip=true` is needed because the inherited enforcer rule
demands JDK 8 while the build actually runs on 17 — upstream's own 6.21.5 release
was built with JDK 17 too (see `Build-Jdk` in its MANIFEST).

The plugin uploads a deployment bundle and stops. Nothing is public yet: go to
**Deployments** in the Portal, check the validation result, then press **Publish**.
Propagation to `repo1.maven.org` takes a few minutes to a few hours.

## After publishing

Recompute the suppression hash — every rebuild changes it:

```bash
sha1sum target/jasperreports-6.21.6.jar
```

and paste it into the sha1 variant in
`tests/cve-2026-6009/downstream-suppression.xml`.

## Consuming it

```xml
<dependency>
  <groupId>io.github.audichuang</groupId>
  <artifactId>jasperreports</artifactId>
  <version>6.21.6</version>
</dependency>
```

Exclude the upstream artifact wherever a transitive dependency drags it in —
different groupIds mean Maven will not treat them as the same artifact and both
will land on the classpath:

```bash
mvn dependency:tree -Dincludes=:jasperreports
```
