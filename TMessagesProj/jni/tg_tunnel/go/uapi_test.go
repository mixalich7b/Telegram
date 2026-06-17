package main

import (
	"strings"
	"testing"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

func TestAmneziaWGUAPIAcceptedByPinnedRuntime(t *testing.T) {
	local, err := parseAddrs([]string{"10.0.0.2/32"})
	if err != nil {
		t.Fatalf("parseAddrs failed: %v", err)
	}
	tunDev, _, err := netstack.CreateNetTUN(local, nil, 1420)
	if err != nil {
		t.Fatalf("CreateNetTUN failed: %v", err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "Telegram/AmneziaWG test: "))
	defer dev.Close()

	config := strings.Join([]string{
		"private_key=" + hexKey("01"),
		"replace_peers=true",
		"jc=4",
		"jmin=50",
		"jmax=100",
		"s1=87",
		"s2=65",
		"s3=43",
		"s4=21",
		"h1=1000000000-1000000001",
		"h2=2000000000-2000000002",
		"h3=3000000000-3000000003",
		"h4=4000000000-4000000004",
		"i1=<b f6ab><d ignored><ds ignored><dz 2><t ignored>",
		"i2=<r 8>",
		"i3=<rd 4>",
		"i4=<rc 5>",
		"i5=<b 0x0a0b>",
		"public_key=" + hexKey("02"),
		"endpoint=127.0.0.1:51820",
		"persistent_keepalive_interval=25",
		"replace_allowed_ips=true",
		"allowed_ip=0.0.0.0/0",
	}, "\n") + "\n"

	if err := dev.IpcSet(config); err != nil {
		t.Fatalf("IpcSet rejected AmneziaWG UAPI: %v", err)
	}
}

func hexKey(byteHex string) string {
	return strings.Repeat(byteHex, 32)
}
