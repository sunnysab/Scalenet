// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

/**
 * Protects a socket from being captured by the VPN.
 *
 * Return 0 on success. Non-zero indicates failure.
 */
fun interface FootprintSocketProtector {
  fun protect(fd: Int): Int
}

