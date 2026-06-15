//
// Created by Ashwin M on 15/06/26.
//

#include "TimelineResolver.h"
#include <android/log.h>

#define LOG_TAG "TimelineResolver"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

TimelineResolver::TimelineResolver() {}

TimelineResolver::~TimelineResolver() {
    Release();
}

void TimelineResolver::SetComposition(const std::vector<NativeTrack>& tracks) {
    std::lock_guard<std::mutex> lock(mResolverMutex);
    mTracks = tracks;

    // Prune decoder pool: remove decoders for files that are no longer in the timeline
    auto it = mDecoderPool.begin();
    while (it != mDecoderPool.end()) {
        bool fileStillExists = false;
        for (const auto& track : mTracks) {
            for (const auto& clip : track.clips) {
                if (clip.filePath == it->first) {
                    fileStillExists = true;
                    break;
                }
            }
            if (fileStillExists) break;
        }

        if (!fileStillExists) {
            LOGI("Pruning decoder from pool: %s", it->first.c_str());
            it->second->Release();
            it = mDecoderPool.erase(it);
        } else {
            ++it;
        }
    }
}

bool TimelineResolver::ResolveFrameAtTime(int64_t timeMs, GLuint oesTextureId, NativeClip& outRenderParams) {
    std::lock_guard<std::mutex> lock(mResolverMutex);

    NativeClip* targetClip = nullptr;

    // Search tracks backwards (top layers first) to find the visible clip
    for (auto trackIt = mTracks.rbegin(); trackIt != mTracks.rend(); ++trackIt) {
        if (trackIt->type != 0) continue; // For now, only resolve Video Tracks (Type 0)

        for (auto& clip : trackIt->clips) {
            if (timeMs >= clip.timelineStartMs && timeMs <= clip.timelineEndMs) {
                targetClip = &clip;
                break;
            }
        }
        if (targetClip) break;
    }

    if (!targetClip) {
        return false; // No video clip active at this timestamp
    }

    // Populate render configurations
    outRenderParams = *targetClip;

    // Get or create decoder for target file path
    auto poolIt = mDecoderPool.find(targetClip->filePath);
    VideoDecoder* decoder = nullptr;

    if (poolIt == mDecoderPool.end()) {
        LOGI("Initializing new decoder in pool for asset: %s", targetClip->filePath.c_str());
        auto newDecoder = std::make_unique<VideoDecoder>();
        if (!newDecoder->Prepare(targetClip->filePath, oesTextureId)) {
            LOGE("Failed to prepare hardware decoder for: %s", targetClip->filePath.c_str());
            return false;
        }
        decoder = newDecoder.get();
        mDecoderPool[targetClip->filePath] = std::move(newDecoder);
    } else {
        decoder = poolIt->second.get();
    }

    // Calculate source relative frame position based on clip trim and speed modifications
    int64_t elapsedOnTimelineMs = timeMs - targetClip->timelineStartMs;
    int64_t sourcePositionMs = targetClip->sourceInMs + (elapsedOnTimelineMs * targetClip->speed);
    int64_t sourcePositionUs = sourcePositionMs * 1000;

    // Trigger frame decode
    return decoder->DecodeNextFrame(sourcePositionUs);
}

void TimelineResolver::Release() {
    std::lock_guard<std::mutex> lock(mResolverMutex);
    for (auto& pair : mDecoderPool) {
        pair.second->Release();
    }
    mDecoderPool.clear();
    mTracks.clear();
}
