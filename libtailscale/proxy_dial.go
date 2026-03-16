// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"errors"
	"net"
	"os"
	"syscall"
)

func dialProxyTCPv4Conn(appCtx AppContext, dstIP4Be, dstPortBe int32, hostname, routeProfile string, timeoutMs, flags int32) (net.Conn, error) {
	fd, err := appCtx.DialProxyTCPv4(dstIP4Be, dstPortBe, hostname, routeProfile, timeoutMs, flags)
	if err != nil {
		return nil, err
	}
	if fd < 0 {
		return nil, errors.New("proxy dial returned negative fd")
	}

	f := os.NewFile(uintptr(fd), "scalenet-proxy-dial")
	if f == nil {
		_ = syscall.Close(int(fd))
		return nil, errors.New("os.NewFile returned nil")
	}
	defer f.Close()

	// net.FileConn duplicates the file descriptor (on Unix platforms), so closing
	// f doesn't tear down the returned connection.
	c, err := net.FileConn(f)
	if err != nil {
		return nil, err
	}
	return c, nil
}

