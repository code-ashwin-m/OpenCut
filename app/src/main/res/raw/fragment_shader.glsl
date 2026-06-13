#extension GL_OES_EGL_image_external : require

precision mediump float;

uniform samplerExternalOES uTexture;
uniform float uBrightness;

uniform float uContrast;

varying vec2 vTexCoord;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    color.rgb += uBrightness;
    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
    gl_FragColor = color;
}
