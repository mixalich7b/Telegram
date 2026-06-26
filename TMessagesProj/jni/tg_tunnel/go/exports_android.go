//go:build android && cgo

package main

// #include <stdint.h>
// #include <stdlib.h>
// #include <string.h>
import "C"

import "unsafe"

//export tgWgStart
func tgWgStart(userspaceConfig *C.char, localAddresses **C.char, localAddressCount C.int, dnsServers **C.char, dnsServerCount C.int, mtu C.int, socksHost *C.char, socksPort C.int, socksUsername *C.char, socksPassword *C.char) C.int {
	return C.int(startWireGuardRuntime(
		C.GoString(userspaceConfig),
		cStringArray(localAddresses, localAddressCount),
		cStringArray(dnsServers, dnsServerCount),
		int(mtu),
		C.GoString(socksHost),
		int(socksPort),
		C.GoString(socksUsername),
		C.GoString(socksPassword)))
}

//export tgWgStop
func tgWgStop() {
	stopRuntime()
}

//export tgWgOnNetworkChanged
func tgWgOnNetworkChanged() C.int {
	return C.int(onNetworkChangedRuntime())
}

//export tgAwgStart
func tgAwgStart(userspaceConfig *C.char, localAddresses **C.char, localAddressCount C.int, dnsServers **C.char, dnsServerCount C.int, mtu C.int, socksHost *C.char, socksPort C.int, socksUsername *C.char, socksPassword *C.char) C.int {
	return C.int(startAmneziaWGRuntime(
		C.GoString(userspaceConfig),
		cStringArray(localAddresses, localAddressCount),
		cStringArray(dnsServers, dnsServerCount),
		int(mtu),
		C.GoString(socksHost),
		int(socksPort),
		C.GoString(socksUsername),
		C.GoString(socksPassword)))
}

//export tgAwgStop
func tgAwgStop() {
	stopRuntime()
}

//export tgAwgOnNetworkChanged
func tgAwgOnNetworkChanged() C.int {
	return C.int(onNetworkChangedRuntime())
}

//export tgTunnelIsRunning
func tgTunnelIsRunning() C.int {
	if tunnelIsRunning() {
		return 1
	}
	return 0
}

//export tgTunnelGetLocalAddress
func tgTunnelGetLocalAddress(index C.int, host *C.char, hostLen C.int) C.int {
	if host == nil || hostLen <= 0 || index < 0 {
		return -1
	}
	addresses := tunnelLocalAddresses()
	i := int(index)
	if i >= len(addresses) {
		return 0
	}
	value := addresses[i].String()
	copyCString(host, hostLen, value)
	if addresses[i].Is4() {
		return 4
	}
	return 6
}

//export tgTunnelTcpConnect
func tgTunnelTcpConnect(host *C.char, port C.int) C.longlong {
	id, err := openTunnelTCP(C.GoString(host), int(port))
	if err != nil {
		logError("tunnel TCP connect failed: %v", err)
		return -1
	}
	return C.longlong(id)
}

//export tgTunnelTcpRead
func tgTunnelTcpRead(id C.longlong, data unsafe.Pointer, length C.int) C.int {
	if data == nil || length <= 0 {
		return -1
	}
	buffer := unsafe.Slice((*byte)(data), int(length))
	n, err := tunnelTCPRead(int64(id), buffer)
	if err != nil {
		return -1
	}
	return C.int(n)
}

//export tgTunnelTcpWrite
func tgTunnelTcpWrite(id C.longlong, data unsafe.Pointer, length C.int) C.int {
	if data == nil || length < 0 {
		return -1
	}
	buffer := unsafe.Slice((*byte)(data), int(length))
	n, err := tunnelTCPWrite(int64(id), buffer)
	if err != nil {
		return -1
	}
	return C.int(n)
}

//export tgTunnelTcpClose
func tgTunnelTcpClose(id C.longlong) {
	closeTunnelTCP(int64(id))
}

//export tgTunnelUdpOpen
func tgTunnelUdpOpen(host *C.char, port C.int) C.longlong {
	id, err := openTunnelUDP(C.GoString(host), int(port))
	if err != nil {
		logError("tunnel UDP open failed: %v", err)
		return -1
	}
	return C.longlong(id)
}

//export tgTunnelUdpSendTo
func tgTunnelUdpSendTo(id C.longlong, data unsafe.Pointer, length C.int, host *C.char, port C.int) C.int {
	if data == nil || length < 0 {
		return -1
	}
	buffer := unsafe.Slice((*byte)(data), int(length))
	n, err := tunnelUDPSendTo(int64(id), buffer, C.GoString(host), int(port))
	if err != nil {
		return -1
	}
	return C.int(n)
}

//export tgTunnelUdpRecvFrom
func tgTunnelUdpRecvFrom(id C.longlong, data unsafe.Pointer, length C.int, host *C.char, hostLen C.int, port *C.int) C.int {
	if data == nil || length <= 0 || host == nil || hostLen <= 0 || port == nil {
		return -1
	}
	buffer := unsafe.Slice((*byte)(data), int(length))
	n, remoteHost, remotePort, err := tunnelUDPRecvFrom(int64(id), buffer)
	if err != nil {
		return -1
	}
	copyCString(host, hostLen, remoteHost)
	*port = C.int(remotePort)
	return C.int(n)
}

//export tgTunnelUdpLocalAddress
func tgTunnelUdpLocalAddress(id C.longlong, host *C.char, hostLen C.int, port *C.int) C.int {
	if host == nil || hostLen <= 0 || port == nil {
		return -1
	}
	localHost, localPort, err := tunnelUDPLocalAddr(int64(id))
	if err != nil {
		return -1
	}
	copyCString(host, hostLen, localHost)
	*port = C.int(localPort)
	return 0
}

//export tgTunnelUdpClose
func tgTunnelUdpClose(id C.longlong) {
	closeTunnelUDP(int64(id))
}

func cStringArray(values **C.char, count C.int) []string {
	if values == nil || count <= 0 {
		return nil
	}
	raw := unsafe.Slice(values, int(count))
	result := make([]string, 0, len(raw))
	for _, value := range raw {
		result = append(result, C.GoString(value))
	}
	return result
}

func copyCString(dst *C.char, dstLen C.int, value string) {
	if dst == nil || dstLen <= 0 {
		return
	}
	bytes := []byte(value)
	max := int(dstLen) - 1
	if len(bytes) > max {
		bytes = bytes[:max]
	}
	if len(bytes) > 0 {
		C.memcpy(unsafe.Pointer(dst), unsafe.Pointer(&bytes[0]), C.size_t(len(bytes)))
	}
	*(*byte)(unsafe.Add(unsafe.Pointer(dst), len(bytes))) = 0
}
