//
// Created by Ashwin M on 15/06/26.
//

#ifndef OPENCUT_VIDEODECODER_H
#define OPENCUT_VIDEODECODER_H

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <string>
#include <mutex>
#include <atomic>

class VideoDecoder {
public:
    VideoDecoder();
    ~VideoDecoder();

    // Initializes extractor and hardware decoder for a target file path
    bool Prepare(const std::string& filePath, GLuint oesTextureId);

    // Decodes and advances to the next sequential frame
    // Returns true on success, false if EOF is reached or an error occurs
    bool DecodeNextFrame(int64_t targetPresentationTimeUs);

    // Forces a seek to a specific timestamp in microseconds (I-frame accurate + forward decode)
    bool SeekTo(int64_t timeUs);

    // Releases all native codec and extractor resources safely
    void Release();

    int64_t GetDurationUs() const { return mDurationUs; }
    int GetVideoWidth() const { return mVideoWidth; }
    int GetVideoHeight() const { return mVideoHeight; }

private:
    bool SetupCodec(const char* mimeType);
    void FlushBuffers();

    AMediaExtractor* mExtractor;
    AMediaCodec* mCodec;

    std::string mFilePath;
    GLuint mOesTextureId;

    int mVideoTrackIndex;
    int mVideoWidth;
    int mVideoHeight;
    int64_t mDurationUs;

    bool mIsExtractorEof;
    bool mIsCodecEof;

    std::mutex mDecoderMutex;
};

#endif //OPENCUT_VIDEODECODER_H
