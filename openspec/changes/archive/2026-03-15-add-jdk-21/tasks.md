## 1. Base Packer Image

- [x] 1.1 Add `openjdk-21-jdk openjdk-21-dbg` to the apt install line in `packer/base/base.pkr.hcl`

## 2. use-cassandra Script

- [x] 2.1 Add `elif [ "$JAVA_VERSION" = "21" ]` branch to `packer/cassandra/bin/use-cassandra` that calls `sudo update-java-alternatives -s java-1.21.0-openjdk-$ARCH`

## 3. cassandra_versions.yaml

- [x] 3.1 Update `trunk` entry in `packer/cassandra/cassandra_versions.yaml` from `java: "17"` to `java: "21"`

## 4. Documentation

- [x] 4.1 No user-facing docs require update — JDK version management is an internal packer detail not documented separately
