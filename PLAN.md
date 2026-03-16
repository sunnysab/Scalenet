# PLAN：在 Android 端同时集成 Tailscale + Proxy（footprint-ffi）

## 背景与目标

本 fork 目标是在同一个 Android 应用内 **同时具备**：

- 现有的 Tailscale 功能（登录、tailnet 连接、MagicDNS、子网路由/出口节点等保留为可选能力）。
- 额外的 Proxy 出站能力（由 `/home/sunnysab/Code/footprint/footprint-ffi` 提供 FFI 拨号器），并支持 **按域名规则**决定 `direct` 或 `proxy`。

约束（当前确定）：

- 需要影响全系统所有 App（Android 上只能运行一个 `VpnService`，因此必须走“单 VPN + 用户态分流”的架构）。
- 仅支持 **IPv4 + TCP**（IPv6 后期再做；UDP 第一阶段不做）。
- 域名分流信号来自 DNS（DNS-only）：`miss` 默认 `direct`，不拦截 Android Private DNS / DoH。
- “Tailscale 网段永远优先”应以 **Tailscale 下发的路由前缀**为准，不硬编码 `100.x` 网段。

## 非目标（第一阶段不做）

- UDP 分流、QUIC 支持。
- 拦截/解密 TLS 获取 SNI（不做 TLS ClientHello 嗅探）。
- 拦截 Private DNS / DoH。
- IPv6 全链路。
- “按 App 分流”（仅 `VpnService.Builder` 的 allow/disallow 维持原状）。

## 现状梳理（关键代码点）

- VPN 配置/建立：
  - `libtailscale/net.go`：`updateTUN` 调 `VpnService.Builder.addRoute/addDnsServer/addAddress + establish`，并把 tun FD 直接交给 wireguard-go（`tun.CreateUnmonitoredTUNFromFD`）。
  - `android/src/main/java/com/tailscale/ipn/IPNService.kt`：`Libtailscale.requestVPN(this)` 触发 Go 侧获取 `protect()` 能力。
  - `libtailscale/backend.go`：`netns.SetAndroidProtectFunc` + `DebugRebind()`。
- LocalAPI（Kotlin → Go）：
  - `android/src/main/java/com/tailscale/ipn/ui/localapi/Client.kt` ↔ `libtailscale/localapi.go`。
- DNS（用于 Tailscale 自身）：
  - `android/src/main/java/com/tailscale/ipn/NetworkChangeCallback.kt` & `DnsConfig.java`：采集底层网络 DNS。
  - `libtailscale/net.go`：把 Go 侧生成的 DNS OSConfig 写进 VPN Builder。
- Proxy（footprint-ffi）：
  - `footprint-ffi/include/footprint_ffi.h`：`fp_engine_*` + `fp_engine_dial_tcp_v4`（返回可读写 fd），支持 `protect` 回调。

## 总体架构（目标态）

### 核心思路：单 VPN + TUN Demux + 双数据面

接管 `0.0.0.0/0` 后，所有流量进入同一个 TUN。我们需要一个 “TUN demux/dispatcher”：

- **Tailscale path（优先）**：目的 IP 命中 Tailscale 路由前缀（来自 `router.Config.Routes` 等）→ 交给 wgengine（WireGuard/magicsock 维持不变）。
- **Internet TCP path（IPv4/TCP）**：其余 IPv4 TCP 连接 → 进入用户态 TCP 处理器：
  - `direct`：通过受 `protect()` 的 socket 直连目的 `dst_ip:dst_port`。
  - `proxy`：通过 footprint-ffi `fp_engine_dial_tcp_v4` 拨号（footprint 内部根据 `hostname`/规则决定 direct/proxy；本项目仍保留上层“强制 direct/proxy”的扩展点）。

### DNS-only 域名分流：proxy IP 集合（带 TTL）

由于不做 TLS SNI/Host 嗅探：

- 通过观测 DNS（优先处理 UDP 53；TCP 53 可后补）得到 `QNAME`。
- 若域名命中“走 proxy”的规则，则把响应里的 A（后续可加 AAAA）写入一个 `proxyIPs` TTL 缓存：
  - key: `IPv4`
  - value: `expiresAt`（使用 DNS RR TTL + clamp）
  - 容量上限 + 定期清理，避免 CDN 域名撑爆集合。
- TCP 连接分流时：`dstIP ∈ proxyIPs => proxy`，否则 `direct`（但 Tailscale 前缀永远优先）。

## 里程碑与任务拆解

### M0：工程接入 footprint-ffi（不改 VPN 数据面）
目标：让 Android app 能加载并配置 footprint engine，且能完成一次 `dial` 返回 fd。

- Android 打包：
  - 把 `libfootprint_ffi.so`（多 ABI）打进 `android` 工程（`jniLibs/` 或 externalNativeBuild）。
- JNI glue：
  - 实现 `protect(fd)` 回调：调用 `IPNService.protect(fd)`。
  - 可选实现 `log(level,msg)` 回调：写入 logcat。
- Kotlin wrapper：
  - `FootprintEngine` 单例：`new / setConfigToml / setRouteProfile / dialTcpV4`。
  - 基础错误处理：`err_code/err_msg/debug_json` 打印或上报 UI。

验收：
- 在不启 VPN 的情况下（或启 VPN 也可以），能从 Kotlin 调 `dial` 返回一个可读写 fd，并完成简单回环/连通性测试（本地或公网取决于环境）。

### M1：为 Go 数据面新增“代理拨号”桥接（AppContext 扩展）
目标：Go 能请求 Android 执行 footprint-ffi dial 并拿到 fd。

- 扩展 `libtailscale/interfaces.go` 的 `AppContext`：
  - 新增 `DialProxyTCPv4(dstIPBe uint32, dstPortBe uint16, hostname string, timeoutMs int) (fd int32, err string)`（具体签名需与 gomobile bind 兼容）。
- Android `App.kt` 实现该方法：调用 Kotlin wrapper，返回 fd。
- 重新生成 AAR（`gomobile bind`）确保接口可用。

验收：
- Go 侧能从 `AppContext` 获得一个可读写 fd（通过简单测试调用链或日志验证）。

### M2：实现 full-tunnel 下的 direct TCP（IPv4/TCP）
目标：接管 `0.0.0.0/0` 后，至少能 direct 访问互联网（TCP）。

关键点：这一步意味着“真实 TUN 不再直接交给 wgengine”，必须引入 TUN dispatcher。

- 设计并落地一个“真实 TUN → 分发器”：
  - 真实 TUN 的 read loop 做包解析/分类。
  - 写回真实 TUN 的单一出口。
- 引入一个 IPv4/TCP 用户态处理器（建议 gVisor netstack；仓库已依赖 gvisor）：
  - 对非 Tailscale 目的的 TCP，建立对应的 userspace socket（direct），并做双向 copy。
  - 所有外连 socket 必须 `protect`（Go 侧可复用已存在的 `netns.SetAndroidProtectFunc`）。

验收：
- 开启 full-tunnel（0.0.0.0/0）后，普通网页 TCP 能 direct 通（UDP/QUIC 失败可接受）。

### M3：把 wgengine（Tailscale）接入 dispatcher，并保证“永远优先”
目标：full-tunnel 下 Tailscale 仍然可用，并且 tailnet/subnet/service IP 等永远走 Tailscale。

- 从 Tailscale backend 侧拿到路由前缀集合（来源：`router.Config.Routes`，以及必要的 service IP）。
- dispatcher 分类优先级：
  1) Tailscale prefix 命中 → wgengine
  2) 否则 IPv4/TCP → Internet TCP handler（direct/proxy 决策）
  3) 否则（UDP/其它）→ 丢弃或最小实现（第一阶段可 drop + 统计）

验收：
- full-tunnel 开启时：
  - tailnet 节点互通 OK
  - MagicDNS / quad-100 不被打断
  - 同时普通互联网 TCP direct OK

### M4：DNS-only 域名分流（proxyIPs TTL 集合）
目标：按域名规则让命中的站点走 proxy，其它走 direct（miss=direct）。

- DNS 观测：
  - 在 dispatcher 中识别 DNS query/response（先做 UDP 53）。
  - 从响应提取 A 记录 + TTL。
- 规则引擎：
  - 第一阶段可以“把规则判定完全交给 footprint”：遇到 DNS query 时调用 footprint 的 route（可通过一次 `dial` 的 debug_json 侧推，或后续在 footprint-ffi 增加 `route_only` API）。
  - 也可以在 app 内实现一个轻量 domain matcher（可选；但会有两套规则源）。
- `proxyIPs`：
  - TTL 缓存 + 容量上限 + 清理。
- TCP 分流：
  - `dstIP ∈ proxyIPs => proxy`（调用 footprint-ffi dial 获取 fd）否则 direct。

验收：
- 配置一条域名规则后，访问该域名（经 DNS）走 proxy；未命中域名走 direct。

### M5：UI/可用性与可观测性
目标：让用户能配置、调试与回滚。

- UI 最小集：
  - Proxy 总开关
  - footprint TOML（或简化规则编辑器→生成 TOML）
  - 运行状态页（连接数、最近命中域名、proxyIPs 大小、错误统计）
- 日志：
  - footprint `debug_json` 在失败时展示/可导出
  - 关键路径加计数器（DNS 命中/未命中、direct/proxy 连接数等）

验收：
- 不连电脑也能从 UI 判断“为什么没走 proxy”（至少能看到 miss 的原因：无 DNS 记录/Private DNS/无 A 记录/TTL 过期等）。

## 关键风险与取舍

- Android API 版本差异：`excludeRoute` 仅 API 33+，低版本 full-tunnel 时 “允许局域网” 体验会受限。
- DNS-only 的天然漏判：
  - Private DNS / DoH 应用会绕过系统 DNS，导致 `proxyIPs` 不更新 → `miss=direct` 直连（符合当前约束，但需要 UI 明确告知）。
  - CDN 共享 IP 可能导致过度代理/不足代理；TTL 缓存策略与容量上限需谨慎。
- 性能：
  - full-tunnel + userspace TCP 必然有开销；需要关注拷贝次数、缓冲区大小、goroutine/协程数量与 GC 压力。
- 兼容性：
  - exit node / RouteAll 与本方案的 full-tunnel 语义可能冲突，需要产品定义（例如禁止同时开启、或明确优先级）。

## 后续扩展（第二阶段）

- IPv6：AAAA 记录 + v6 TCP 栈 + `fp_engine_dial_tcp_v6`。
- UDP：按需支持 DNS 以外 UDP（或 QUIC）。
- “route-only” FFI：在 footprint-ffi 增加 `fp_engine_route(hostname, ip)`，避免用 `dial` 做规则查询。
- 更准确的域名信号：在可接受时增加 SNI/Host 兜底（不是当前目标）。

