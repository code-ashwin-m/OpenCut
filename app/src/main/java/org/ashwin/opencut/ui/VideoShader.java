package org.ashwin.opencut.ui;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class VideoShader {

    private static final float[] VERTICES = {
            // X, Y, U, V

            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,

            1f, -1f, 1f, 1f,
            1f,  1f, 1f, 0f,
            -1f,  1f, 0f, 0f
    };

    private final FloatBuffer vertexBuffer;

    private final int program;

    private final int positionHandle;
    private final int texCoordHandle;

    private final int textureHandle;
    private final int brightnessHandle;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "\n" +
                    "varying vec2 vTexCoord;\n" +
                    "\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "\n" +
                    "precision mediump float;\n" +
                    "\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "uniform float uBrightness;\n" +
                    "\n" +
                    "varying vec2 vTexCoord;\n" +
                    "\n" +
                    "void main() {\n" +
                    "\n" +
                    "    vec4 color = texture2D(uTexture, vTexCoord);\n" +
                    "\n" +
                    "    color.rgb += uBrightness;\n" +
                    "\n" +
                    "    gl_FragColor = color;\n" +
                    "}";

    public VideoShader() {

        ByteBuffer bb = ByteBuffer.allocateDirect(
                VERTICES.length * 4
        );

        bb.order(ByteOrder.nativeOrder());

        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(VERTICES);
        vertexBuffer.position(0);

        int vertexShader =
                loadShader(
                        GLES20.GL_VERTEX_SHADER,
                        VERTEX_SHADER
                );

        int fragmentShader =
                loadShader(
                        GLES20.GL_FRAGMENT_SHADER,
                        FRAGMENT_SHADER
                );

        program = GLES20.glCreateProgram();

        GLES20.glAttachShader(
                program,
                vertexShader
        );

        GLES20.glAttachShader(
                program,
                fragmentShader
        );

        GLES20.glLinkProgram(program);

        positionHandle =
                GLES20.glGetAttribLocation(
                        program,
                        "aPosition"
                );

        texCoordHandle =
                GLES20.glGetAttribLocation(
                        program,
                        "aTexCoord"
                );

        textureHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uTexture"
                );

        brightnessHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uBrightness"
                );
    }

    public void draw(
            int textureId,
            float brightness
    ) {

        GLES20.glUseProgram(program);

        vertexBuffer.position(0);

        GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(
                positionHandle
        );

        vertexBuffer.position(2);

        GLES20.glVertexAttribPointer(
                texCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(
                texCoordHandle
        );

        GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0
        );

        GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                textureId
        );

        GLES20.glUniform1i(
                textureHandle,
                0
        );

        GLES20.glUniform1f(
                brightnessHandle,
                brightness
        );

        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLES,
                0,
                6
        );

        GLES20.glDisableVertexAttribArray(
                positionHandle
        );

        GLES20.glDisableVertexAttribArray(
                texCoordHandle
        );
    }

    private int loadShader(
            int type,
            String shaderCode
    ) {

        int shader =
                GLES20.glCreateShader(type);

        GLES20.glShaderSource(
                shader,
                shaderCode
        );

        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];

        GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                compiled,
                0
        );

        if(compiled[0] == 0){

            Log.e(
                    "GL",
                    GLES20.glGetShaderInfoLog(shader)
            );
        }

        return shader;
    }

    public void release() {

        GLES20.glDeleteProgram(program);
    }
}