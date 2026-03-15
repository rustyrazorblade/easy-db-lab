## 1. Install JDK 21 in Base Packer Image

- [ ] 1.1 In `packer/base/base.pkr.hcl`, add `openjdk-21-jdk openjdk-21-dbg` to the inline apt install provisioner alongside the existing 8/11/17 packages. Keep JDK 11 as the post-install default (the `update-java-alternatives` call on the next line stays unchanged).

## 2. Add JDK 21 Support to `use-cassandra`

- [ ] 2.1 In `packer/cassandra/bin/use-cassandra`, add an `elif [ "$JAVA_VERSION" = "21" ]` branch that runs `sudo update-java-alternatives -s java-1.21.0-openjdk-$ARCH`, following the existing pattern for 8, 11, and 17.

## 3. Update `cassandra_versions.yaml`

- [ ] 3.1 In `packer/cassandra/cassandra_versions.yaml`, change the `trunk` entry's `java` field from `"17"` to `"21"`.

## 4. Documentation

- [ ] 4.1 Check `docs/` for any page that lists supported JDK versions on cluster nodes (e.g., packer or AMI reference docs) and update to include JDK 21.
- [ ] 4.2 Update `CLAUDE.md` Development Setup section if it references the JDK versions available on nodes (currently it only describes the dev toolchain JDKs; add a note that nodes also ship JDK 21).

## 5. Verify

- [ ] 5.1 Run `./gradlew testPackerBase` to verify the base image provisioning scripts pass with JDK 21 added (Docker-based test, no AWS required).
- [ ] 5.2 Confirm `use-cassandra trunk` can be invoked in the packer test environment without hitting "Unknown java version".
