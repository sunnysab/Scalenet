// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math/bits"
	"net"
	"net/http"
	"strconv"
	"strings"
)

func (a *App) handleScaleNetLocalAPI(w http.ResponseWriter, r *http.Request) bool {
	if r == nil || r.URL == nil {
		return false
	}

	switch r.URL.Path {
	case "/localapi/v0/scalenet/debug/dial-proxy-tcpv4":
		a.handleScaleNetDebugDialProxyTCPv4(w, r)
		return true
	default:
		return false
	}
}

type scaleNetDebugDialProxyTCPv4Response struct {
	OK bool `json:"ok"`
	ConnType string `json:"connType,omitempty"`
}

func (a *App) handleScaleNetDebugDialProxyTCPv4(w http.ResponseWriter, r *http.Request) {
	writeErr := func(status int, msg string) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
	}

	q := r.URL.Query()
	ipStr := strings.TrimSpace(q.Get("ip"))
	portStr := strings.TrimSpace(q.Get("port"))
	if ipStr == "" || portStr == "" {
		writeErr(http.StatusBadRequest, "missing ip/port")
		return
	}
	ip := net.ParseIP(ipStr)
	if ip == nil {
		writeErr(http.StatusBadRequest, "invalid ip")
		return
	}
	ip4 := ip.To4()
	if ip4 == nil {
		writeErr(http.StatusBadRequest, "ipv6 not supported")
		return
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		writeErr(http.StatusBadRequest, "invalid port")
		return
	}

	timeoutMs := int32(0)
	if s := strings.TrimSpace(q.Get("timeoutMs")); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil || v < 0 {
			writeErr(http.StatusBadRequest, "invalid timeoutMs")
			return
		}
		timeoutMs = int32(v)
	}
	flags := int32(0)
	if s := strings.TrimSpace(q.Get("flags")); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil || v < 0 {
			writeErr(http.StatusBadRequest, "invalid flags")
			return
		}
		flags = int32(v)
	}

	hostname := strings.TrimSpace(q.Get("hostname"))
	routeProfile := strings.TrimSpace(q.Get("profile"))

	// The footprint FFI wants big-endian bytes in memory. Since the Android ABI is
	// little-endian, pass the host-order numeric values that produce big-endian
	// bytes when written to native structs.
	ip4BeBytes := binary.BigEndian.Uint32(ip4)
	dstIP4Be := int32(bits.ReverseBytes32(ip4BeBytes))
	dstPortBe := int32(bits.ReverseBytes16(uint16(port)))

	c, derr := dialProxyTCPv4Conn(a.appCtx, dstIP4Be, dstPortBe, hostname, routeProfile, timeoutMs, flags)
	if derr != nil {
		writeErr(http.StatusInternalServerError, derr.Error())
		return
	}

	connType := fmt.Sprintf("%T", c)
	_ = c.Close() // debug endpoint: close immediately
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(scaleNetDebugDialProxyTCPv4Response{
		OK:       true,
		ConnType: connType,
	})
}
