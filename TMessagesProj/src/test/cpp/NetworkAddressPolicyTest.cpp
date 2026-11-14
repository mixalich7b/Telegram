#include "tgnet/NetworkAddressPolicy.h"
#include <cassert>

int main() {
    assert(tgnet::ipv6Enabled());
    tgnet::setIpv6Enabled(false);
    assert(tgnet::effectiveIpStrategy(1) == 0);
    assert(tgnet::effectiveIpStrategy(2) == 0);
    assert(!tgnet::addressAllowed("2001:db8::1"));
    assert(!tgnet::addressAllowed("::ffff:192.0.2.1"));
    assert(!tgnet::addressAllowed("[2001:db8::1]"));
    assert(tgnet::addressAllowed("192.0.2.1"));
    assert(tgnet::addressAllowed("proxy.example.test"));
    tgnet::setIpv6Enabled(true);
    assert(tgnet::effectiveIpStrategy(2) == 2);
    assert(tgnet::addressAllowed("2001:db8::1"));
}
