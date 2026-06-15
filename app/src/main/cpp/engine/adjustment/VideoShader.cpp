//
// Created by Ashwin M on 15/06/26.
//

#include "VideoShader.h"

#include "VideoShader.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "ShaderEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// --- GLSL Vertex Shader Source ---
// Handles texture coordinates mapping and applying the transformation matrix
const char* VERTEX_SHADER_SOURCE = R"glsl(#version 300 es
    in vec3 inPosition;
    in vec2 inTexCoord;
    out vec2 fragTexCoord;

    uniform mat4 uTransformMatrix;

    void main() {
        gl_Position = uTransformMatrix * vec4(inPosition, 1.0);
        fragTexCoord = inTexCoord;
    }
)glsl";

// --- GLSL Fragment Shader Source ---
// Renders the Android hardware texture and applies brightness, contrast, and saturation
const char* FRAGMENT_SHADER_SOURCE = R"glsl(#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision mediump float;

    in vec2 fragTexCoord;
    out vec4 fragColor;

    uniform samplerExternalOES uTexture;

    // Adjustment Uniforms
    uniform float uBrightness;  // Offset (-1.0 to 1.0)
    uniform float uContrast;    // Scale (0.0 to 2.0)
    uniform float uSaturation;  // Interpolate (0.0 to 2.0)

    // Standard Luma constants for grayscale calculations
    const vec3 lumaWeights = vec3(0.299, 0.587, 0.114);

    void main() {
        // Read hardware-decoded frame directly
        vec4 texColor = texture(uTexture, fragTexCoord);
        vec3 rgb = texColor.rgb;

        // 1. Apply Brightness (Additive offset)
        rgb += uBrightness;

        // 2. Apply Contrast (Scale relative to mid-gray)
        rgb = (rgb - 0.5) * uContrast + 0.5;

        // 3. Apply Saturation (Luminance blend)
        float luminance = dot(rgb, lumaWeights);
        vec3 grayscale = vec3(luminance);
        rgb = mix(grayscale, rgb, uSaturation);

        // Clamp values to keep colors in valid output ranges
        fragColor = vec4(clamp(rgb, 0.0, 1.0), texColor.a);
    }
)glsl";

VideoShader::VideoShader() :
        mProgramId(0), mVao(0), mVbo(0), mEbo(0),
        mPositionLink(-1), mTexCoordLink(-1), mMatrixUniform(-1), mTextureUniform(-1),
        mBrightnessUniform(-1), mContrastUniform(-1), mSaturationUniform(-1) {}

VideoShader::~VideoShader() {
    Release();
}

bool VideoShader::Initialize() {
    GLuint vertexShader = CompileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
    if (!vertexShader) return false;

    GLuint fragmentShader = CompileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE);
    if (!fragmentShader) {
        glDeleteShader(vertexShader);
        return false;
    }

    mProgramId = glCreateProgram();
    glAttachShader(mProgramId, vertexShader);
    glAttachShader(mProgramId, fragmentShader);
    glLinkProgram(mProgramId);

    GLint linkStatus;
    glGetProgramiv(mProgramId, GL_LINK_STATUS, &linkStatus);
    if (linkStatus != GL_TRUE) {
        char logBuffer[512];
        glGetProgramInfoLog(mProgramId, 512, nullptr, logBuffer);
        LOGE("Shader program linking failed: %s", logBuffer);
        return false;
    }

    // Free compiler handles once linked
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    // Get Uniform & Attribute location indices
    mPositionLink = glGetAttribLocation(mProgramId, "inPosition");
    mTexCoordLink = glGetAttribLocation(mProgramId, "inTexCoord");
    mMatrixUniform = glGetUniformLocation(mProgramId, "uTransformMatrix");
    mTextureUniform = glGetUniformLocation(mProgramId, "uTexture");

    mBrightnessUniform = glGetUniformLocation(mProgramId, "uBrightness");
    mContrastUniform = glGetUniformLocation(mProgramId, "uContrast");
    mSaturationUniform = glGetUniformLocation(mProgramId, "uSaturation");

    SetupVertexBuffers();
    LOGI("OpenGL ES GLSL Color & Transform Shader initialized successfully.");
    return true;
}

void VideoShader::SetupVertexBuffers() {
    // Quad coordinates mapping Normalized Device Coordinates (NDC)
    // Vertices [X, Y, Z] + UV mapping [U, V] (inverted Y for video orientation)
    float vertices[] = {
            -1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Top-Left
            -1.0f, -1.0f, 0.0f,  0.0f, 1.0f, // Bottom-Left
            1.0f, -1.0f, 0.0f,  1.0f, 1.0f, // Bottom-Right
            1.0f,  1.0f, 0.0f,  1.0f, 0.0f  // Top-Right
    };

    unsigned int indices[] = {
            0, 1, 2,
            0, 2, 3
    };

    glGenVertexArrays(1, &mVao);
    glGenBuffers(1, &mVbo);
    glGenBuffers(1, &mEbo);

    glBindVertexArray(mVao);

    glBindBuffer(GL_ARRAY_BUFFER, mVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mEbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(indices), indices, GL_STATIC_DRAW);

    // Position pointer layout mapping
    glVertexAttribPointer(mPositionLink, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(mPositionLink);

    // Texture UV pointer layout mapping
    glVertexAttribPointer(mTexCoordLink, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void*)(3 * sizeof(float)));
    glEnableVertexAttribArray(mTexCoordLink);

    glBindVertexArray(0);
}

void VideoShader::CalculateTransformMatrix(
        float* outMatrix,
        float scaleX, float scaleY,
        float rotationDegrees,
        float translationX, float translationY,
        float viewportWidth, float viewportHeight
) {
    // Identity Matrix initialization
    for (int i = 0; i < 16; i++) outMatrix[i] = 0.0f;
    outMatrix[0] = 1.0f; outMatrix[5] = 1.0f; outMatrix[10] = 1.0f; outMatrix[15] = 1.0f;

    // Convert pixels translation offsets to OpenGL NDC space (-1.0 to 1.0)
    float normTx = (translationX / viewportWidth) * 2.0f;
    float normTy = (translationY / viewportHeight) * 2.0f;

    float rad = rotationDegrees * (M_PI / 180.0f);
    float cosR = cos(rad);
    float sinR = sin(rad);

    // Combined TRS (Translation * Rotation * Scale) Matrix transformation formula
    outMatrix[0] = cosR * scaleX;
    outMatrix[1] = sinR * scaleX;
    outMatrix[4] = -sinR * scaleY;
    outMatrix[5] = cosR * scaleY;

    outMatrix[12] = normTx;
    outMatrix[13] = normTy;
}

void VideoShader::DrawFrame(
        GLuint oesTextureId,
        float scaleX, float scaleY,
        float rotationDegrees,
        float translationX, float translationY,
        float brightness, float contrast, float saturation,
        int viewportWidth, int viewportHeight
) {
    glUseProgram(mProgramId);

    // 1. Calculate and upload modern Transformation Matrix
    float transformMatrix[16];
    CalculateTransformMatrix(transformMatrix, scaleX, scaleY, rotationDegrees, translationX, translationY, viewportWidth, viewportHeight);
    glUniformMatrix4fv(mMatrixUniform, 1, GL_FALSE, transformMatrix);

    // 2. Upload Filter Uniform variables
    glUniform1f(mBrightnessUniform, brightness);
    glUniform1f(mContrastUniform, contrast);
    glUniform1f(mSaturationUniform, saturation);

    // 3. Bind Android External OES Texture target
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId);
    glUniform1i(mTextureUniform, 0);

    // 4. Issue OpenGL Draw Calls
    glBindVertexArray(mVao);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
    glBindVertexArray(0);
}

GLuint VideoShader::CompileShader(GLenum type, const char* source) {
    GLuint shaderId = glCreateShader(type);
    glShaderSource(shaderId, 1, &source, nullptr);
    glCompileShader(shaderId);

    GLint compileStatus;
    glGetShaderiv(shaderId, GL_COMPILE_STATUS, &compileStatus);
    if (compileStatus != GL_TRUE) {
        char logBuffer[512];
        glGetShaderInfoLog(shaderId, 512, nullptr, logBuffer);
        LOGE("Shader compilation failed for type %d: %s", type, logBuffer);
        glDeleteShader(shaderId);
        return 0;
    }
    return shaderId;
}

void VideoShader::Release() {
    if (mProgramId) {
        glDeleteProgram(mProgramId);
        mProgramId = 0;
    }
    if (mVbo) {
        glDeleteBuffers(1, &mVbo);
        mVbo = 0;
    }
    if (mEbo) {
        glDeleteBuffers(1, &mEbo);
        mEbo = 0;
    }
    if (mVao) {
        glDeleteVertexArrays(1, &mVao);
        mVao = 0;
    }
}
