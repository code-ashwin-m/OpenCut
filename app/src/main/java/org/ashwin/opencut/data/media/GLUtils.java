package org.ashwin.opencut.data.media;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

public class GLUtils {
    public static int createOESTexture() {
        int[] tex = new int[1];

        GLES20.glGenTextures(1, tex, 0);

        GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                tex[0]
        );

        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
        );

        GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
        );

        return tex[0];
    }
}
