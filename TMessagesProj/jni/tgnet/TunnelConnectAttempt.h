#pragma once

#include <atomic>
#include <cstdint>

namespace tgnet {

// Outcome ownership is separate from socket cleanup and runtime state.
class TunnelConnectAttempt {
public:
    enum class Result { None, Connected, Failed, Cancelled };
    explicit TunnelConnectAttempt(int64_t generation) : generation_(generation) { }
    int64_t generation() const { return generation_; }
    bool complete(Result result) {
        return result != Result::None && !completed_.exchange(true);
    }
    Result close(int reason) {
        const Result result = reason == 0 ? Result::Cancelled : Result::Failed;
        return complete(result) ? result : Result::None;
    }
private:
    const int64_t generation_;
    std::atomic<bool> completed_{false};
};

} // namespace tgnet
