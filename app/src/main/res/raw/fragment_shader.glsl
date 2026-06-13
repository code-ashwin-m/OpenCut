#extension GL_OES_EGL_image_external : require

precision mediump float;

uniform samplerExternalOES uTexture;
uniform float uBrightness;

varying vec2 vTexCoord;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    color.rgb += uBrightness;
    gl_FragColor = color;
}
