#pragma once

#include <string>

namespace opencut {

class OpenCutEngine {
public:
    OpenCutEngine() = default;

    std::string engineInfo() const;
    void prepare();
    void release();

private:
    bool prepared_ = false;
};

}  // namespace opencut
