package org.ashwin.opencut.data.media;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import org.ashwin.opencut.R;
import org.ashwin.opencut.domain.model.EffectSettings;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;

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

    private int mvpMatrixHandle;

    private String readTextFileFromRawResource(Context context, int resourceId) {
        InputStream inputStream = context.getResources().openRawResource(resourceId);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }

    public VideoShader(Context context) {

        ByteBuffer bb = ByteBuffer.allocateDirect(
                VERTICES.length * 4
        );

        bb.order(ByteOrder.nativeOrder());

        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(VERTICES);
        vertexBuffer.position(0);

        String vertexShaderCode = readTextFileFromRawResource(context, R.raw.vertex_shader);
        String fragmentShaderCode = readTextFileFromRawResource(context, R.raw.fragment_shader);

        int vertexShader =
                loadShader(
                        GLES20.GL_VERTEX_SHADER,
                        vertexShaderCode
                );

        int fragmentShader =
                loadShader(
                        GLES20.GL_FRAGMENT_SHADER,
                        fragmentShaderCode
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

        mvpMatrixHandle =
                GLES20.glGetUniformLocation(
                        program,
                        "uMVPMatrix"
                );
    }

    public void draw(
            int textureId,
            EffectSettings effectSettings,
            float[] mvpMatrix) {

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

        GLES20.glUniformMatrix4fv(
                mvpMatrixHandle,
                1,
                false,
                mvpMatrix,
                0
        );

        GLES20.glUniform1i(
                textureHandle,
                0
        );

        float brightness = effectSettings != null ? effectSettings.brightness : 0f;
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