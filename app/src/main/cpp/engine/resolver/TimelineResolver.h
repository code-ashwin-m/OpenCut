//
// Created by Ashwin M on 15/06/26.
//

#ifndef OPENCUT_TIMELINERESOLVER_H
#define OPENCUT_TIMELINERESOLVER_H


#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <memory>
#include "decoder/VideoDecoder.h"

struct NativeClip {
    std::string id;
    std::string filePath;
    int64_t sourceInMs;
    int64_t sourceOutMs;
    int64_t timelineStartMs;
    int64_t timelineEndMs;
    int64_t speed;

    // Transform parameters
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    float rotation = 0.0f;
    float translationX = 0.0f;
    float translationY = 0.0f;

    // Filter adjustments
    float brightness = 0.0f;
    float contrast = 1.0f;
    float saturation = 1.0f;
};

struct NativeTrack {
    std::string id;
    int type; // 0 for Video, 1 for Audio, etc.
    std::vector<NativeClip> clips;
};

class TimelineResolver {
public:
    TimelineResolver();
    ~TimelineResolver();

    // Rebuilds the composition track metadata from the serialized configuration
    void SetComposition(const std::vector<NativeTrack>& tracks);

    // Determines active clips at timestamp T and decodes their current frames
    // into the supplied OES texture ID. Returns the metadata for rendering.
    bool ResolveFrameAtTime(int64_t timeMs, GLuint oesTextureId, NativeClip& outRenderParams);

    // Safely clears all allocated hardware decoders
    void Release();

private:
    std::vector<NativeTrack> mTracks;

    // Decoder caching system mapped by: ClipFilePath -> DecoderInstance
    // This allows seamless clip transitions without tearing down the hardware context
    std::unordered_map<std::string, std::unique_ptr<VideoDecoder>> mDecoderPool;

    std::mutex mResolverMutex;
};



#endif //OPENCUT_TIMELINERESOLVER_H
