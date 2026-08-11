// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import com.fitbit.goldengate.bindings.dtls.TlsKeyResolver
import com.fitbit.goldengate.bindings.node.NodeKey
import java.util.concurrent.atomic.AtomicReference

/**
 * Records only an unresolved DTLS PSK identity. It never supplies a key and therefore cannot
 * change authentication behavior. PSK identities are identifiers, not the AES key material.
 */
object DiagnosticTlsIdentityRecorder : TlsKeyResolver() {
    private val lastIdentity = AtomicReference<ByteArray?>()

    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        lastIdentity.set(keyId.copyOf())
        return null
    }

    fun clear() {
        lastIdentity.set(null)
    }

    fun renderedIdentity(): String? {
        val bytes = lastIdentity.get() ?: return null
        val printable = bytes.isNotEmpty() && bytes.all { (it.toInt() and 0xff) in 0x20..0x7e }
        return if (printable) {
            bytes.toString(Charsets.UTF_8).take(MAX_RENDERED_IDENTITY_LENGTH)
        } else {
            "hex:" + bytes.take(MAX_RENDERED_IDENTITY_LENGTH / 2)
                .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        }
    }

    private const val MAX_RENDERED_IDENTITY_LENGTH = 96
}
