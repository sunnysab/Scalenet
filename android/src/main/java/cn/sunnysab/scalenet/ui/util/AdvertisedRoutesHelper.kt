// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.util

import cn.sunnysab.scalenet.ui.model.Ipn

class AdvertisedRoutesHelper {
  companion object {
    fun exitNodeOnFromPrefs(prefs: Ipn.Prefs): Boolean {
      var v4 = false
      var v6 = false
      prefs.AdvertiseRoutes?.forEach {
        if (it == "0.0.0.0/0") {
          v4 = true
        }
        if (it == "::/0") {
          v6 = true
        }
      }
      return v4 && v6
    }
  }
}
