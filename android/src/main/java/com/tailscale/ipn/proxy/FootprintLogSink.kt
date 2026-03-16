// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

/**
 * Receives logs from the footprint FFI engine.
 *
 * The message is provided as UTF-8 bytes.
 */
fun interface FootprintLogSink {
  fun log(level: Int, msgUtf8: ByteArray)
}

