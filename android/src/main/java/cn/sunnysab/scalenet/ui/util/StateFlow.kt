// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Provides a way to expose a MutableStateFlow as an immutable StateFlow. */
fun <T> StateFlow<T>.set(v: T) {
  (this as MutableStateFlow<T>).value = v
}
