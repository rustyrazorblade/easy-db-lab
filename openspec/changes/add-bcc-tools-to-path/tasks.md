## 1. Update /etc/environment PATH

- [ ] 1.1 Add `/usr/share/bcc/tools` to the PATH in `packer/cassandra/environment`

## 2. Verification

- [ ] 2.1 Run `./gradlew testPackerCassandra` (or relevant packer test) to verify the provisioning script still works after the change
