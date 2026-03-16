// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.sunnysab.scalenet.App
import cn.sunnysab.scalenet.ui.model.Health
import cn.sunnysab.scalenet.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HealthViewModel : ViewModel() {
  val warnings: StateFlow<List<Health.UnhealthyState>> = MutableStateFlow(listOf())

  init {
    viewModelScope.launch {
      App.get().healthNotifier?.currentWarnings?.collect { set -> warnings.set(set.sorted()) }
    }
  }
}
