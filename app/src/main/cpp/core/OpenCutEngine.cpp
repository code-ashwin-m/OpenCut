#include "OpenCutEngine.h"

namespace opencut {

std::string OpenCutEngine::engineInfo() const {
    return prepared_
            ? "OpenCut native engine v1 (prepared)"
            : "OpenCut native engine v1";
}

void OpenCutEngine::prepare() {
    prepared_ = true;
}

void OpenCutEngine::release() {
    prepared_ = false;
}

}  // namespace opencut
