// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.sunnysab.scalenet.ui.model.StableNodeID
import cn.sunnysab.scalenet.ui.model.Tailcfg
import cn.sunnysab.scalenet.ui.notifier.Notifier
import cn.sunnysab.scalenet.ui.util.ComposableStringFormatter
import cn.sunnysab.scalenet.ui.util.set
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PeerSettingInfo(val titleRes: Int, val value: ComposableStringFormatter)

class PeerDetailsViewModelFactory(
    private val nodeId: StableNodeID,
    private val filesDir: File,
    private val pingViewModel: PingViewModel
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return PeerDetailsViewModel(nodeId, filesDir, pingViewModel) as T
  }
}

class PeerDetailsViewModel(
    val nodeId: StableNodeID,
    val filesDir: File,
    val pingViewModel: PingViewModel
) : IpnViewModel() {
  val node: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)
  val isPinging: StateFlow<Boolean> = MutableStateFlow(false)

  init {
    viewModelScope.launch {
      Notifier.netmap.collect { nm ->
        netmap.set(nm)
        nm?.getPeer(nodeId)?.let { peer -> node.set(peer) }
      }
    }
  }

  fun startPing() {
    isPinging.set(true)
    node.value?.let { this.pingViewModel.startPing(it) }
  }

  fun onPingDismissal() {
    isPinging.set(false)
    this.pingViewModel.handleDismissal()
  }
}
