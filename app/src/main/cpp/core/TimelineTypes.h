#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace opencut {

enum class TrackType {
    Video,
    Audio,
    Adjustment,
};

struct TimeRange {
    std::int64_t startMicros = 0;
    std::int64_t durationMicros = 0;
};

struct ClipSnapshot {
    std::string id;
    std::string assetId;
    TrackType trackType = TrackType::Video;
    TimeRange sourceRange;
    TimeRange timelineRange;
};

struct TimelineSnapshot {
    double frameRate = 30.0;
    std::vector<ClipSnapshot> clips;
};

}  // namespace opencut
