#include "VideoRenderer.h"
#include <android/log.h>

namespace opencut {

static const float VERTICES[] = {
        // X, Y, U, V
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,

         1.0f, -1.0f, 1.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 0.0f
};

const char* VERTEX_SHADER_CODE = R"(
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uMVPMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTexCoord = aTexCoord;
}
)";

const char* FRAGMENT_SHADER_CODE = R"(
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uBrightness;
uniform float uContrast;
uniform float uExposure;
uniform float uHighlights;
uniform float uShadows;
varying vec2 vTexCoord;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    color.rgb = color.rgb * pow(2.0, uExposure);

    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    float shadow = uShadows * (1.0 - smoothstep(0.0, 1.0, luminance));
    float highlight = uHighlights * smoothstep(0.0, 1.0, luminance);
    color.rgb += (shadow + highlight) * color.rgb;

    color.rgb += uBrightness;
    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
    gl_FragColor = color;
}
)";

VideoRenderer::~VideoRenderer() {
    release();
}

void VideoRenderer::init() {
    if (program_ != 0) {
        release();
    }
    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, VERTEX_SHADER_CODE);
    GLuint fragmentShader = loadShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE);

    program_ = glCreateProgram();
    glAttachShader(program_, vertexShader);
    glAttachShader(program_, fragmentShader);
    glLinkProgram(program_);

    GLint linked;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(program_, infoLen, nullptr, infoLog);
            __android_log_print(ANDROID_LOG_ERROR, "GL", "Error linking program:\n%s\n", infoLog);
            delete[] infoLog;
        }
        glDeleteProgram(program_);
        program_ = 0;
        return;
    }

    positionHandle_ = glGetAttribLocation(program_, "aPosition");
    texCoordHandle_ = glGetAttribLocation(program_, "aTexCoord");
    textureHandle_ = glGetUniformLocation(program_, "uTexture");
    brightnessHandle_ = glGetUniformLocation(program_, "uBrightness");
    contrastHandle_ = glGetUniformLocation(program_, "uContrast");
    exposureHandle_ = glGetUniformLocation(program_, "uExposure");
    highlightsHandle_ = glGetUniformLocation(program_, "uHighlights");
    shadowsHandle_ = glGetUniformLocation(program_, "uShadows");
    mvpMatrixHandle_ = glGetUniformLocation(program_, "uMVPMatrix");
}

void VideoRenderer::draw(int textureId, const EffectSettings& effectSettings, const float* mvpMatrix) {
    if (program_ == 0) return;

    glUseProgram(program_);

    glVertexAttribPointer(
            positionHandle_,
            2,
            GL_FLOAT,
            GL_FALSE,
            16,
            VERTICES
    );
    glEnableVertexAttribArray(positionHandle_);

    glVertexAttribPointer(
            texCoordHandle_,
            2,
            GL_FLOAT,
            GL_FALSE,
            16,
            VERTICES + 2
    );
    glEnableVertexAttribArray(texCoordHandle_);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);

    glUniformMatrix4fv(
            mvpMatrixHandle_,
            1,
            GL_FALSE,
            mvpMatrix
    );

    glUniform1i(textureHandle_, 0);

    glUniform1f(brightnessHandle_, effectSettings.brightness);
    glUniform1f(contrastHandle_, effectSettings.contrast);
    glUniform1f(exposureHandle_, effectSettings.exposure);
    glUniform1f(highlightsHandle_, effectSettings.highlights);
    glUniform1f(shadowsHandle_, effectSettings.shadows);

    glDrawArrays(GL_TRIANGLES, 0, 6);

    glDisableVertexAttribArray(positionHandle_);
    glDisableVertexAttribArray(texCoordHandle_);
}

void VideoRenderer::release() {
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
}

GLuint VideoRenderer::loadShader(GLenum type, const char* shaderCode) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &shaderCode, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == 0) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            __android_log_print(ANDROID_LOG_ERROR, "GL", "Error compiling shader:\n%s\n", infoLog);
            delete[] infoLog;
        }
    }
    return shader;
}

} // namespace opencut
