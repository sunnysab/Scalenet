// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.proxy

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class FootprintEngine(
    protector: FootprintSocketProtector? = null,
    logger: FootprintLogSink? = null,
) : AutoCloseable {
  companion object {
    const val ACTION_UNKNOWN = 0
    const val ACTION_DIRECT = 1
    const val ACTION_PROXY = 2

    const val FLAG_PREFER_HOSTNAME = 1 shl 0
    const val FLAG_DISABLE_DEBUG_JSON = 1 shl 1

    private fun decodeUtf8(bytes: ByteArray?): String? {
      if (bytes == null || bytes.isEmpty()) return null
      return bytes.toString(StandardCharsets.UTF_8)
    }

    fun ipv4ToBeInt(a: Int, b: Int, c: Int, d: Int): Int {
      val be =
          (a and 0xff shl 24) or (b and 0xff shl 16) or (c and 0xff shl 8) or (d and 0xff)
      return Integer.reverseBytes(be)
    }

    fun portToBeShort(port: Int): Int {
      val v = port.toShort()
      return (java.lang.Short.reverseBytes(v).toInt() and 0xffff)
    }
  }

  data class DialResult(
      val fd: Int,
      val action: Int,
      val errCode: Int,
      val errMsg: String?,
      val debugJson: String?,
  )

  private val handle = AtomicLong(0L)

  init {
    val outErr: Array<ByteArray?> = arrayOfNulls(1)
    val h = FootprintNative.nativeCreateEngine(protector, logger, outErr)
    if (h == 0L) {
      val msg = decodeUtf8(outErr[0]) ?: "failed to create footprint engine"
      throw IllegalStateException(msg)
    }
    handle.set(h)
  }

  fun setConfigToml(toml: String) {
    val h = handle.get()
    require(h != 0L) { "engine is closed" }
    val err = FootprintNative.nativeSetConfigToml(h, toml.toByteArray(StandardCharsets.UTF_8))
    if (err != null) {
      throw IllegalArgumentException(decodeUtf8(err) ?: "failed to set config")
    }
  }

  fun setRouteProfile(profile: String) {
    val h = handle.get()
    require(h != 0L) { "engine is closed" }
    val err = FootprintNative.nativeSetRouteProfile(h, profile)
    if (err != null) {
      throw IllegalArgumentException(decodeUtf8(err) ?: "failed to set route profile")
    }
  }

  fun dialTcpV4(
      dstIp4Be: Int,
      dstPortBe: Int,
      hostname: String? = null,
      routeProfile: String? = null,
      timeoutMs: Int = 0,
      flags: Int = 0,
  ): DialResult {
    val h = handle.get()
    require(h != 0L) { "engine is closed" }
    val res =
        FootprintNative.nativeDialTcpV4(h, dstIp4Be, dstPortBe, hostname, routeProfile, timeoutMs, flags)
    return DialResult(
        fd = res.fd,
        action = res.action,
        errCode = res.errCode,
        errMsg = decodeUtf8(res.errMsgUtf8),
        debugJson = decodeUtf8(res.debugJsonUtf8),
    )
  }

  override fun close() {
    val h = handle.getAndSet(0L)
    if (h != 0L) {
      FootprintNative.nativeFreeEngine(h)
    }
  }
}
