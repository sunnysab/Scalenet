// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.proxy

/**
 * Receives logs from the footprint FFI engine.
 *
 * The message is provided as UTF-8 bytes.
 */
fun interface FootprintLogSink {
  fun log(level: Int, msgUtf8: ByteArray)
}

