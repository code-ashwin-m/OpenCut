#pragma once

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include "EffectSettings.h"

namespace opencut {

class VideoRenderer {
public:
    VideoRenderer() = default;
    ~VideoRenderer();

    void init();
    void draw(int textureId, const EffectSettings& effectSettings, const float* mvpMatrix);
    void release();

private:
    GLuint loadShader(GLenum type, const char* shaderCode);

    GLuint program_ = 0;
    GLint positionHandle_ = -1;
    GLint texCoordHandle_ = -1;
    GLint textureHandle_ = -1;
    GLint brightnessHandle_ = -1;
    GLint contrastHandle_ = -1;
    GLint exposureHandle_ = -1;
    GLint highlightsHandle_ = -1;
    GLint shadowsHandle_ = -1;
    GLint mvpMatrixHandle_ = -1;
};

} // namespace opencut
