// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.sunnysab.scalenet.ui.localapi.Client
import cn.sunnysab.scalenet.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BugReportViewModel : ViewModel() {
  val bugReportID: StateFlow<String> = MutableStateFlow("")

  init {
    Client(viewModelScope).bugReportId { result ->
      result
          .onSuccess { bugReportID.set(it.trim()) }
          .onFailure { bugReportID.set("(Error fetching ID)") }
    }
  }
}
