//
// Created by Ashwin M on 17/06/26.
//

#ifndef OPENCUT_PROJECTCORE_H
#define OPENCUT_PROJECTCORE_H
// In your C++ code
struct TimelineClip {
    char asset_uri[256];
    long duration_ms;
};

struct Project {
    char project_id[64];
    TimelineClip clips[50]; // Fixed size or use std::vector
    int clip_count;
};
#endif //OPENCUT_PROJECTCORE_H
