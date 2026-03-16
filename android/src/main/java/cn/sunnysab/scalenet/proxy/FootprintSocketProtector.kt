// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.proxy

/**
 * Protects a socket from being captured by the VPN.
 *
 * Return 0 on success. Non-zero indicates failure.
 */
fun interface FootprintSocketProtector {
  fun protect(fd: Int): Int
}

