package com.rustyrazorblade.easydblab.providers.ssh

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.File

/**
 * The OpenSSH public key line (`ssh-rsa AAAA...`) of a private key file. A cloud key pair can be
 * imported from it, so a machine launched with that key pair accepts the same private key.
 */
object OpenSshPublicKey {
    /** The public key of the private key in [privateKeyFile]; throws when the file holds no key. */
    fun of(privateKeyFile: File): String {
        val keyPair =
            SecurityUtils
                .getKeyPairResourceParser()
                .loadKeyPairs(null, privateKeyFile.toPath(), null)
                .firstOrNull()
                ?: throw IllegalArgumentException("${privateKeyFile.absolutePath} holds no private key")
        return PublicKeyEntry.toString(keyPair.public)
    }
}
