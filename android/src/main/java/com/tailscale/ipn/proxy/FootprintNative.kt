// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

internal object FootprintNative {
  init {
    System.loadLibrary("footprintjni")
  }

  data class DialResult(
      val fd: Int,
      val action: Int,
      val errCode: Int,
      val errMsgUtf8: ByteArray?,
      val debugJsonUtf8: ByteArray?,
  )

  external fun nativeCreateEngine(
      protector: FootprintSocketProtector?,
      logger: FootprintLogSink?,
      outErrUtf8: Array<ByteArray?>,
  ): Long

  external fun nativeFreeEngine(handle: Long)

  external fun nativeSetConfigToml(handle: Long, tomlUtf8: ByteArray): ByteArray?

  external fun nativeSetRouteProfile(handle: Long, profile: String): ByteArray?

  external fun nativeDialTcpV4(
      handle: Long,
      dstIp4Be: Int,
      dstPortBe: Int,
      hostname: String?,
      routeProfile: String?,
      timeoutMs: Int,
      flags: Int,
  ): DialResult
}

