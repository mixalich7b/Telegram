//go:build android && cgo

package main

// #include <stdlib.h>
import "C"

import "unsafe"

//export tgAwgStart
func tgAwgStart(userspaceConfig *C.char, localAddresses **C.char, localAddressCount C.int, dnsServers **C.char, dnsServerCount C.int, mtu C.int, socksHost *C.char, socksPort C.int, socksUsername *C.char, socksPassword *C.char) C.int {
	return C.int(startRuntime(
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
