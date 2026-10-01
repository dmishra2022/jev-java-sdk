# Releasing to Maven Central

Artifacts are published through the [Central Publisher Portal](https://central.sonatype.com) with the
`central-publishing-maven-plugin`. Published: `jev-sdk-parent` (pom), `jev-sdk`, `jev-sdk-structured`, each with
sources, javadoc, GPG signatures and checksums. `jev-sample` is never published.

## One-time setup

### 1. Push the code to GitHub

The POM's `<scm>` and `<url>` point to `https://github.com/dmishra2022/jev-java-sdk`. Create that repository (or
update the POM), then:

```bash
git add -A && git commit -m "Initial import" && git branch -M main
git remote add origin git@github.com:dmishra2022/jev-java-sdk.git && git push -u origin main
```

### 2. Claim the namespace

1. Sign in at <https://central.sonatype.com> **with GitHub** as `dmishra2022`.
2. *Namespaces*: `io.github.dmishra2022` is verified automatically for GitHub sign-ins. If not, add it and create
   the public repository the portal asks for (named after the verification key), then press *Verify*.

`io.github.<user>` is the only groupId you can use without owning a domain. To use your own domain, register it
in the portal (DNS TXT verification) and change `<groupId>` and the Java packages.

### 3. Create a portal token

*Account* → *Generate User Token*. Put it in `~/.m2/settings.xml` (the server id must be `central`):

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

This is the token, not your login password.

### 4. Create and publish a signing key

```bash
gpg --full-generate-key                        # RSA 4096 (or ed25519), your name + dharmishra@yahoo.com
gpg --list-secret-keys --keyid-format=long     # note the key id, e.g. ABCD1234EF567890
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234EF567890
gpg --keyserver keys.openpgp.org --send-keys ABCD1234EF567890   # then confirm the e-mail it sends you
```

Central rejects signatures from keys it cannot find on a public key server. Keep a backup of the secret key and
a revocation certificate.

## Releasing from your machine

```bash
export MAVEN_GPG_PASSPHRASE='...'               # read by maven-gpg-plugin; never put it in a POM
./mvnw versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
./mvnw -Prelease clean deploy                   # tests + 100% coverage gate, then sign and upload
```

This uploads and **validates** but does not publish (`autoPublish=false`). Open *Deployments* in the portal,
inspect the files, then press *Publish*. You can also press *Drop* to discard. Once you trust the pipeline, add
`-Dcentral.autoPublish=true`.

After the release is published:

```bash
git commit -am "Release 0.1.0" && git tag v0.1.0
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Next development version" && git push && git push --tags
```

Artifacts appear on `repo1.maven.org` within about 30 minutes; search.maven.org can take a few hours.
**A published version can never be changed or deleted.** If something is wrong, release a new version.

## Releasing from GitHub Actions

`.github/workflows/release.yml` runs on a pushed `v*` tag, sets the version from the tag and publishes with
`autoPublish=true`. Add these repository secrets (ideally in a `maven-central` environment with required
reviewers):

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Portal token password |
| `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys ABCD1234EF567890` |
| `MAVEN_GPG_PASSPHRASE` | The key's passphrase |

Then: `git tag v0.1.0 && git push origin v0.1.0`.

## Checklist

- [ ] `./mvnw verify` is green (tests, 100% coverage, enforcer).
- [ ] `./mvnw -Prelease -Dgpg.skip verify` builds the sources and javadoc jars (CI runs this on every push).
- [ ] The version has no `-SNAPSHOT` (snapshots use the portal's separate snapshot repository, which must be
      enabled per namespace).
- [ ] README install snippet shows the new version.

## Notes on Java 26 preview

`jev-sdk-structured` is compiled with `--enable-preview`, so its class files only load on **JDK 26** with
`--enable-preview`. Consumers on JDK 27+ need a build of this module compiled for their JDK (preview APIs can
change between releases). `jev-sdk` uses only final features and runs on any JDK 26+.
