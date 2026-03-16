// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet.ui.view

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.sunnysab.scalenet.App
import cn.sunnysab.scalenet.R
import cn.sunnysab.scalenet.proxy.FootprintEngine
import cn.sunnysab.scalenet.proxy.FootprintLogSink
import cn.sunnysab.scalenet.proxy.FootprintSocketProtector
import cn.sunnysab.scalenet.ui.localapi.Request
import cn.sunnysab.scalenet.util.TSLog
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.reflect.typeOf

@Composable
fun FootprintDebugView(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var engine by remember { mutableStateOf<FootprintEngine?>(null) }
  var status by remember { mutableStateOf("") }
  var logs by remember { mutableStateOf("") }
  var lastConfigPath by remember { mutableStateOf<String?>(null) }

  fun appendLog(line: String) {
    logs = (logs + line + "\n").takeLast(16 * 1024)
  }

  val protector =
      FootprintSocketProtector { _ ->
        // Smoke test only. Full-tunnel mode must provide a real VpnService.protect(fd).
        0
      }
  val logSink =
      FootprintLogSink { level, msgUtf8 ->
        val msg = msgUtf8.toString(StandardCharsets.UTF_8)
        appendLog("[L$level] $msg")
      }

  Scaffold(
      topBar = { Header(titleRes = R.string.footprint_debug, onBack = onBack) },
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Button(
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            try {
              engine?.close()
              engine = FootprintEngine(protector = protector, logger = logSink)
              status = "engine: created"
            } catch (t: Throwable) {
              status = "engine: create failed: ${t.message}"
            }
          },
      ) {
        Text(text = stringResource(R.string.footprint_debug_create_engine))
      }

      Button(
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            try {
              val e = engine ?: throw IllegalStateException("engine is null")
              val rulePath = writeTempRuleFile(ctx, "example.com\n")
              lastConfigPath = rulePath.absolutePath
              val toml =
                  """
                  inbounds = []

                  [[route_profiles]]
                  id = "p1"
                  rules = ["r1"]
                  fallback = "direct"

                  [[rules]]
                  id = "r1"
                  ruleset = "${rulePath.absolutePath}"
                  target = "main"

                  [[sources]]
                  id = "s1"
                  priority = 1
                  type = "inline"
                  nodes = []

                  [[proxy_chains]]
                  id = "main"
                  members = ["s1"]
                  """.trimIndent()
              e.setConfigToml(toml)
              e.setRouteProfile("p1")
              App.get().setFootprintConfigTomlAndProfile(toml, "p1")
              status = "config: set ok (${rulePath.absolutePath})"
            } catch (t: Throwable) {
              status = "config: set failed: ${t.message}"
            }
          },
      ) {
        Text(text = stringResource(R.string.footprint_debug_set_sample_config))
      }

      Button(
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            try {
              val e = engine ?: throw IllegalStateException("engine is null")
              val ip = FootprintEngine.ipv4ToBeInt(127, 0, 0, 1)
              val portBe = FootprintEngine.portToBeShort(1)
              val res =
                  e.dialTcpV4(
                      dstIp4Be = ip,
                      dstPortBe = portBe,
                      hostname = "not-example.com",
                      routeProfile = "p1",
                      timeoutMs = 300,
                      flags = FootprintEngine.FLAG_DISABLE_DEBUG_JSON,
                  )
              status =
                  "dial: fd=${res.fd} action=${res.action} err=${res.errCode} msg=${res.errMsg}"
            } catch (t: Throwable) {
              status = "dial: failed: ${t.message}"
            }
          },
      ) {
        Text(text = stringResource(R.string.footprint_debug_dial_test))
      }

      Button(
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            val path =
                "scalenet/debug/dial-proxy-tcpv4" +
                    "?ip=127.0.0.1&port=1" +
                    "&hostname=not-example.com&profile=p1" +
                    "&timeoutMs=300&flags=${FootprintEngine.FLAG_DISABLE_DEBUG_JSON}"
            Request<String>(
                    scope = scope,
                    method = "GET",
                    path = path,
                    responseType = typeOf<String>(),
                ) { result ->
                  status =
                      result.fold(
                          onSuccess = { "go-bridge dial: ok: $it" },
                          onFailure = { "go-bridge dial: failed: ${it.message}" },
                      )
                }
                .execute()
          },
      ) {
        Text(text = stringResource(R.string.footprint_debug_go_bridge_dial_test))
      }

      Text(text = status, style = MaterialTheme.typography.bodyMedium)
      lastConfigPath?.let { Text(text = "rule file: $it", style = MaterialTheme.typography.bodySmall) }
      if (logs.isNotEmpty()) {
        Text(text = logs, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

private fun writeTempRuleFile(ctx: Context, content: String): File {
  val dir = File(ctx.cacheDir, "footprint")
  if (!dir.exists()) {
    dir.mkdirs()
  }
  val f = File(dir, "rules.txt")
  f.writeText(content, Charsets.UTF_8)
  TSLog.d("FootprintDebug", "Wrote temp rule file: ${f.absolutePath}")
  return f
}
