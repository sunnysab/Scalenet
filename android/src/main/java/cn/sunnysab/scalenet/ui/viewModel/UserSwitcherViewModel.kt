// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.viewModel

import cn.sunnysab.scalenet.ui.view.ErrorDialogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UserSwitcherViewModel : IpnViewModel() {

  // Set to a non-null value to show the appropriate error dialog
  val errorDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)

  // True if we should render the kebab menu
  val showHeaderMenu: StateFlow<Boolean> = MutableStateFlow(false)
}
