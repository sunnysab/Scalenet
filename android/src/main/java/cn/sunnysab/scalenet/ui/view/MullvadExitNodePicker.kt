// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.sunnysab.scalenet.R
import cn.sunnysab.scalenet.ui.util.Lists
import cn.sunnysab.scalenet.ui.util.LoadingIndicator
import cn.sunnysab.scalenet.ui.util.flag
import cn.sunnysab.scalenet.ui.util.itemsWithDividers
import cn.sunnysab.scalenet.ui.viewModel.ExitNodePickerNav
import cn.sunnysab.scalenet.ui.viewModel.ExitNodePickerViewModel
import cn.sunnysab.scalenet.ui.viewModel.ExitNodePickerViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MullvadExitNodePicker(
    countryCode: String,
    nav: ExitNodePickerNav,
    model: ExitNodePickerViewModel = viewModel(factory = ExitNodePickerViewModelFactory(nav))
) {
  val mullvadExitNodes by model.mullvadExitNodesByCountryCode.collectAsState()
  val bestAvailableByCountry by model.mullvadBestAvailableByCountry.collectAsState()

  mullvadExitNodes[countryCode]?.toList()?.let { nodes ->
    val any = nodes.first()

    LoadingIndicator.Wrap {
      Scaffold(
          topBar = {
            Header(
                title = { Text("${countryCode.flag()} ${any.country}") },
                onBack = nav.onNavigateBackToMullvad)
          }) { innerPadding ->
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
              if (nodes.size > 1) {
                val bestAvailableNode = bestAvailableByCountry[countryCode]!!
                item {
                  ExitNodeItem(
                      model,
                      ExitNodePickerViewModel.ExitNode(
                          id = bestAvailableNode.id,
                          label = stringResource(R.string.best_available),
                          online = bestAvailableNode.online,
                          selected = false,
                      ))
                  Lists.SectionDivider()
                }
              }

              itemsWithDividers(nodes) { node -> ExitNodeItem(model, node) }
            }
          }
    }
  }
}
