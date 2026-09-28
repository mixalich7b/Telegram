#include "rtc_base/socks5_handshake.h"

#include <cassert>
#include <iostream>
#include <vector>

using rtc::Socks5Handshake;
using Bytes = std::vector<uint8_t>;

static Bytes Drain(Socks5Handshake &client) {
    Bytes bytes(client.pending_data(), client.pending_data() + client.pending_size());
    // A socket can accept only one byte per write.
    while (client.pending_size()) client.Sent(1);
    return bytes;
}

static void Reply(Socks5Handshake &client, const Bytes &reply) {
    assert(client.Receive(reply.data(), reply.size()) == reply.size());
}

int main() {
    const Bytes ipv4{1, 192, 0, 2, 1};
    Socks5Handshake plain(ipv4, 443, "", "");
    assert(Drain(plain) == Bytes({5, 1, 0}));
    const Bytes hello{5, 0};
    assert(plain.Receive(hello.data(), 1) == 0);
    assert(!plain.connected());
    Reply(plain, hello);
    assert(Drain(plain) == Bytes({5, 1, 0, 1, 192, 0, 2, 1, 1, 187}));
    const Bytes connected{5, 0, 0, 1, 127, 0, 0, 1, 0, 80};
    for (size_t n = 0; n < connected.size(); ++n) {
        assert(plain.Receive(connected.data(), n) == 0);
        assert(!plain.connected());
    }
    auto withPayload = connected;
    withPayload.push_back(0xee);
    assert(plain.Receive(withPayload.data(), withPayload.size()) == connected.size());
    assert(plain.connected()); // Coalesced application bytes remain for the socket.

    const Bytes hostname{3, 3, 'r', 't', 'c'};
    Socks5Handshake auth(hostname, 1080, "alice", "secret");
    assert(Drain(auth) == Bytes({5, 2, 0, 2}));
    Reply(auth, {5, 2});
    assert(Drain(auth) == Bytes({1, 5, 'a', 'l', 'i', 'c', 'e', 6, 's', 'e', 'c', 'r', 'e', 't'}));
    Reply(auth, {1, 0});
    assert(Drain(auth) == Bytes({5, 1, 0, 3, 3, 'r', 't', 'c', 4, 56}));
    Reply(auth, {5, 0, 0, 3, 1, 'x', 0, 80});
    assert(auth.connected());

    Bytes ipv6(17, 0);
    ipv6[0] = 4;
    ipv6[16] = 1;
    Socks5Handshake v6(ipv6, 65535, "", "");
    Drain(v6);
    Reply(v6, hello);
    Bytes expected{5, 1, 0};
    expected.insert(expected.end(), ipv6.begin(), ipv6.end());
    expected.insert(expected.end(), {255, 255});
    assert(Drain(v6) == expected);
    Bytes bound{5, 0, 0};
    bound.insert(bound.end(), ipv6.begin(), ipv6.end());
    bound.insert(bound.end(), {0, 80});
    Reply(v6, bound);
    assert(v6.connected());

    for (const Bytes &bad : {Bytes{4, 0}, Bytes{5, 255}, Bytes{5, 2}}) {
        Socks5Handshake denied(ipv4, 443, "", "");
        Drain(denied);
        denied.Receive(bad.data(), bad.size());
        assert(denied.failed() && !denied.connected() && !denied.pending_size());
    }
    Socks5Handshake badAuth(ipv4, 443, "u", "p");
    Drain(badAuth);
    Reply(badAuth, {5, 2});
    Drain(badAuth);
    const Bytes refusal{1, 1};
    badAuth.Receive(refusal.data(), refusal.size());
    assert(badAuth.failed());
    for (const Bytes &bad : {Bytes{5, 5, 0, 1}, Bytes{5, 0, 1, 1}, Bytes{5, 0, 0, 2}, Bytes{5, 0, 0, 3, 0}}) {
        Socks5Handshake denied(ipv4, 443, "", "");
        Drain(denied);
        Reply(denied, hello);
        Drain(denied);
        denied.Receive(bad.data(), bad.size());
        assert(denied.failed() && !denied.connected());
    }
    assert(Socks5Handshake(ipv4, 443, std::string(256, 'u'), "p").failed());
    assert(Socks5Handshake(ipv4, 443, "u", std::string(256, 'p')).failed());
    assert(Socks5Handshake({3, 0}, 443, "", "").failed());
    assert(Socks5Handshake(ipv4, 0, "", "").failed());
    std::cout << "SOCKS5 handshake tests passed\n";
}
