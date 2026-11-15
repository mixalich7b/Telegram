#include "tgnet/TunnelConnectAttempt.h"
#include <atomic>
#include <cassert>
#include <thread>

using tgnet::TunnelConnectAttempt;
using Result = TunnelConnectAttempt::Result;

int main() {
    TunnelConnectAttempt timedOut(7);
    assert(timedOut.close(2) == Result::Failed);
    assert(timedOut.generation() == 7);
    assert(!timedOut.complete(Result::Failed));
    assert(!timedOut.complete(Result::Connected));
    assert(timedOut.close(0) == Result::None);

    TunnelConnectAttempt suspended(8);
    assert(suspended.close(0) == Result::Cancelled);
    TunnelConnectAttempt socketError(8);
    assert(socketError.close(1) == Result::Failed);
    TunnelConnectAttempt connected(9);
    assert(connected.complete(Result::Connected));
    assert(connected.close(2) == Result::None);
    assert(connected.generation() == 9 && timedOut.generation() == 7);

    // Competing terminal callbacks must have exactly one owner.
    for (int i = 0; i < 100; ++i) {
        TunnelConnectAttempt attempt(10);
        std::atomic<int> winners{0};
        std::atomic<bool> go{false};
        std::thread success([&] {
            while (!go.load()) std::this_thread::yield();
            if (attempt.complete(Result::Connected)) ++winners;
        });
        std::thread timeout([&] {
            while (!go.load()) std::this_thread::yield();
            if (attempt.close(2) != Result::None) ++winners;
        });
        go.store(true);
        success.join();
        timeout.join();
        assert(winners.load() == 1);
        assert(attempt.close(0) == Result::None);
    }
}
