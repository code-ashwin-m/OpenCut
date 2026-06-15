//
// Created by Ashwin M on 15/06/26.
//

#ifndef OPENCUT_VIDEOSHADER_H
#define OPENCUT_VIDEOSHADER_H


#pragma once

#include <GLES3/gl3.h>
#define __gl2_h_          // Prevents duplicate gl2.h inclusions & signature collisions
#include <GLES2/gl2ext.h> // Contains GL_TEXTURE_EXTERNAL_OES
#include <GLES3/gl3ext.h>
#include <string>

class VideoShader {
public:
    VideoShader();
    ~VideoShader();

    // Compiles vertex/fragment source programs and links the GPU executable
    bool Initialize();

    // Binds the program, uploads uniform variables, and draws the coordinate quad
    void DrawFrame(GLuint oesTextureId,
                   float scaleX, float scaleY,
                   float rotationDegrees,
                   float translationX, float translationY,
                   float brightness, float contrast, float saturation,
                   int viewportWidth, int viewportHeight);

    // Releases OpenGL assets safely
    void Release();

private:
    GLuint CompileShader(GLenum type, const char* source);
    void SetupVertexBuffers();
    void CalculateTransformMatrix(float* outMatrix,
                                  float scaleX, float scaleY,
                                  float rotationDegrees,
                                  float translationX, float translationY,
                                  float viewportWidth, float viewportHeight);

    GLuint mProgramId;

    // Shader Attribute/Uniform locations
    GLint mPositionLink;
    GLint mTexCoordLink;
    GLint mMatrixUniform;
    GLint mTextureUniform;
    GLint mBrightnessUniform;
    GLint mContrastUniform;
    GLint mSaturationUniform;

    // Vertex Array & Buffer Object Handles
    GLuint mVao;
    GLuint mVbo;
    GLuint mEbo;
};

#endif //OPENCUT_VIDEOSHADER_H
